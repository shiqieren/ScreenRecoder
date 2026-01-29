# MIC 录音问题诊断命令

## 问题描述
screen-recorder-oem 应用的 AudioRecord 初始化成功，状态显示 RECORDING，但读取的数据全是 0（静音）。
SystemUI 录屏功能正常，说明是应用权限/策略问题。

---

## 诊断步骤

### 1. 检查 AppOps 权限状态
```bash
adb shell appops get com.hht.oemscreenrecoder RECORD_AUDIO
```
**预期结果**: 应该显示 `allow`，如果显示 `deny` 或 `ignore` 则是权限被拒绝

### 2. 强制授予 AppOps 权限（如果步骤1显示非allow）
```bash
adb shell appops set com.hht.oemscreenrecoder RECORD_AUDIO allow
```

### 3. 检查所有 AppOps 权限
```bash
adb shell appops get com.hht.oemscreenrecoder
```

---

## SELinux 诊断

### 4. 查看当前 SELinux 模式
```bash
adb shell getenforce
```
**预期结果**: `Enforcing` 或 `Permissive`

### 5. 检查 SELinux 是否阻止了音频访问
```bash
adb logcat -d | grep -i "avc.*denied.*audio"
```

### 6. 更全面地查看 SELinux 拒绝日志
```bash
adb logcat -d | grep -i "avc.*denied"
```

### 7. 查看与该应用相关的 SELinux 拒绝
```bash
adb logcat -d | grep -i "avc.*denied.*oemscreenrecoder"
```

---

## SELinux 临时测试（仅用于调试定位问题）

### 8. 临时禁用 SELinux（设为宽容模式）
```bash
adb shell setenforce 0
```
然后**重新测试录音功能**，如果能录到声音，说明是 SELinux 策略问题。

### 9. 恢复 SELinux（测试完成后务必恢复）
```bash
adb shell setenforce 1
```

---

## 音频系统诊断

### 10. 查看音频设备列表
```bash
adb shell dumpsys audio | grep -A 20 "Audio Devices"
```

### 11. 查看麦克风状态
```bash
adb shell dumpsys audio | grep -i "mic"
```

### 12. 查看音频策略
```bash
adb shell dumpsys media.audio_policy | head -100
```

---

## 应用签名检查

### 13. 检查应用的签名信息
```bash
adb shell dumpsys package com.hht.oemscreenrecoder | grep -i "signature\|platform"
```

### 14. 检查应用的 UID
```bash
adb shell dumpsys package com.hht.oemscreenrecoder | grep -i "userId"
```

### 15. 对比 SystemUI 的 UID
```bash
adb shell dumpsys package com.android.systemui | grep -i "userId"
```

---

## 实时日志监控

### 16. 实时查看 MicRecorder 日志
```bash
adb logcat -s MicRecorder:D
```

### 17. 实时查看所有音频相关日志
```bash
adb logcat | grep -iE "audio|mic|record"
```

---

## 诊断结果记录

请记录以下结果：

1. **AppOps RECORD_AUDIO 状态**: _______________
2. **SELinux 模式**: _______________
3. **是否有 SELinux denied 日志**: _______________
4. **关闭 SELinux 后能否录音**: _______________
5. **应用 UID**: _______________
6. **SystemUI UID**: _______________

---

## 可能的解决方案

### 如果是 AppOps 问题
在系统中预置 AppOps 权限配置

### 如果是 SELinux 问题
需要在设备的 SELinux 策略文件中添加允许规则，例如：
```
allow system_app audio_device:chr_file { read write };
```

### 如果是签名问题
确保应用使用与系统相同的平台签名（platform.keystore）
