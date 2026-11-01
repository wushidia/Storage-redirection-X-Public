// std::string ABI 临时包装：从 FUSE 镜像或已加载的 libc++ 成对解析符号
// libc++ basic_string 全零的 24 字节是合法 SSO 空串，构造无需调用 ctor

use super::image::FuseJniImage;
use std::ffi::{CStr, CString};
use std::os::raw::c_void;

const STD_ASSIGN_NDK: &[u8] =
    b"_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKcm";
const STD_ASSIGN_STD: &[u8] =
    b"_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKcm";
const STD_DTOR_NDK: &[u8] =
    b"_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED1Ev";
const STD_DTOR_STD: &[u8] = b"_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED1Ev";

type AssignFn = unsafe extern "C" fn(*mut c_void, *const u8, usize) -> *mut c_void;
type DtorFn = unsafe extern "C" fn(*mut c_void);

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    // 经典布局：long 字段顺序 data/size/cap+is_long_bit；short 末字节 bit7=is_long
    Classic,
    // 替代布局（LLVM 17+ 切换）：long 字段顺序 cap+is_long_bit/size/data；short 首字节 bit0=is_long
    Alternate,
}

impl Layout {
    pub fn name(self) -> &'static str {
        match self {
            Self::Classic => "classic",
            Self::Alternate => "alternate",
        }
    }
}

#[repr(C, align(8))]
pub struct CxxString {
    storage: [u64; 3],
}

pub struct StringAbi {
    assign: AssignFn,
    dtor: DtorFn,
    layout: Layout,
}

impl StringAbi {
    pub fn resolve(image: &FuseJniImage) -> Option<Self> {
        let (assign_addr, dtor_addr) = [
            (STD_ASSIGN_NDK, STD_DTOR_NDK),
            (STD_ASSIGN_STD, STD_DTOR_STD),
        ]
        .into_iter()
        .find_map(|(assign, dtor)| {
            image
                .find_symbol(assign)
                .zip(image.find_symbol(dtor))
                .or_else(|| resolve_from_libcxx(assign, dtor))
        })?;
        let assign: AssignFn = unsafe { std::mem::transmute::<usize, AssignFn>(assign_addr) };
        let dtor: DtorFn = unsafe { std::mem::transmute::<usize, DtorFn>(dtor_addr) };
        let layout = detect_layout(assign, dtor)?;
        Some(Self {
            assign,
            dtor,
            layout,
        })
    }

    pub fn layout(&self) -> Layout {
        self.layout
    }

    // 在栈上构造 std::string 镜像：零值 24 字节 = 空 SSO，再 assign 写入字节
    // 调用方必须显式 drop，否则可能泄露 heap
    pub unsafe fn construct(&self, bytes: &[u8]) -> CxxString {
        let mut s = CxxString { storage: [0; 3] };
        unsafe {
            (self.assign)(
                &mut s as *mut CxxString as *mut c_void,
                bytes.as_ptr(),
                bytes.len(),
            );
        }
        s
    }

    pub unsafe fn drop_string(&self, value: &mut CxxString) {
        unsafe {
            (self.dtor)(value as *mut CxxString as *mut c_void);
        }
    }
}

fn resolve_from_libcxx(assign: &[u8], dtor: &[u8]) -> Option<(usize, usize)> {
    let assign = CString::new(assign).ok()?;
    let dtor = CString::new(dtor).ok()?;
    let handle = unsafe { libc::dlopen(c"libc++.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD) };
    if !handle.is_null() {
        let result = resolve_pair(handle, &assign, &dtor);
        unsafe { libc::dlclose(handle) };
        if result.is_some() {
            return result;
        }
    }
    let handle = unsafe { srx_inline_hook::sh_dlopen(c"libc++.so") };
    if handle.is_null() {
        return None;
    }
    let assign = unsafe { srx_inline_hook::sh_dlsym(handle, &assign) };
    let dtor = unsafe { srx_inline_hook::sh_dlsym(handle, &dtor) };
    unsafe { srx_inline_hook::sh_dlclose(handle) };
    (!assign.is_null() && !dtor.is_null()).then_some((assign as usize, dtor as usize))
}

fn resolve_pair(handle: *mut c_void, assign: &CStr, dtor: &CStr) -> Option<(usize, usize)> {
    let assign = unsafe { libc::dlsym(handle, assign.as_ptr()) };
    let dtor = unsafe { libc::dlsym(handle, dtor.as_ptr()) };
    (!assign.is_null() && !dtor.is_null()).then_some((assign as usize, dtor as usize))
}

// 短串探针同时验证长度编码与字符位置，未识别布局不安装 Hook。
// 仅在 install 阶段调用一次，构造短串不会触发 heap 分配
fn detect_layout(assign: AssignFn, dtor: DtorFn) -> Option<Layout> {
    let mut short = CxxString { storage: [0; 3] };
    unsafe { assign(&mut short as *mut _ as *mut c_void, b"Z".as_ptr(), 1) };
    let bytes = short.storage.as_ptr() as *const u8;
    let first = unsafe { *bytes };
    let second = unsafe { *bytes.add(1) };
    let last = unsafe { *bytes.add(23) };
    unsafe { dtor(&mut short as *mut _ as *mut c_void) };

    if first == b'Z' && last == 1 {
        return Some(Layout::Classic);
    }
    if first == 2 && second == b'Z' {
        return Some(Layout::Alternate);
    }
    None
}

// 从 const std::string& 读取数据；只处理 long 模式（FUSE 完整路径必然 ≥ 23 字节）
// value 必须是 libc++ basic_string 的有效引用
pub unsafe fn read_cxx_string<'a>(value: *const CxxString, layout: Layout) -> &'a [u8] {
    if value.is_null() {
        return &[];
    }
    let storage = unsafe { &(*value).storage };
    let bytes = storage.as_ptr() as *const u8;
    match layout {
        Layout::Classic => {
            let last = unsafe { *bytes.add(23) };
            if last & 0x80 == 0 {
                let size = (last & 0x7f) as usize;
                if size >= 23 {
                    return &[];
                }
                return unsafe { std::slice::from_raw_parts(bytes, size) };
            }
            let data = storage[0] as *const u8;
            let size = storage[1] as usize;
            if data.is_null() || size > 0x10_0000 {
                return &[];
            }
            unsafe { std::slice::from_raw_parts(data, size) }
        }
        Layout::Alternate => {
            let first = unsafe { *bytes };
            if first & 0x1 == 0 {
                let size = (first >> 1) as usize;
                if size >= 23 {
                    return &[];
                }
                return unsafe { std::slice::from_raw_parts(bytes.add(1), size) };
            }
            let size = storage[1] as usize;
            let data = storage[2] as *const u8;
            if data.is_null() || size > 0x10_0000 {
                return &[];
            }
            unsafe { std::slice::from_raw_parts(data, size) }
        }
    }
}
