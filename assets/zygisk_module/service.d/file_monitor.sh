start_file_monitor_service() {
  (
    umask 077
    export CLASSPATH="$MODDIR/monitor.dex"
    exec /system/bin/app_process /system/bin org.srx.monitor.MonitorServer "$MODDIR"
  ) >>"$LOGS_DIR/monitor-service.log" 2>&1 &
}
