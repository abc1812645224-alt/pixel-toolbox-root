---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 001b83761c348f6205ef0555a74ffa33_987c899697ba11f19467525400287e28
    ReservedCode1: SQsVxkVNtHpM665a7xa0Un/LC37Anpmzxo0sx4/NKfz7xfvGCOsMvTRuas9DaNfnnbRyiItZGrvATI6cG692zkNkTP4P+cKPfetftYlbjlbGaQLpf6Yuim7uUP6cXv3ziB4rxMsdrO3BZDQdA63Ap1rLbMkJ10TO9f9zUIepP8DJ3EieW8FOQhdBr3g=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 001b83761c348f6205ef0555a74ffa33_987c899697ba11f19467525400287e28
    ReservedCode2: SQsVxkVNtHpM665a7xa0Un/LC37Anpmzxo0sx4/NKfz7xfvGCOsMvTRuas9DaNfnnbRyiItZGrvATI6cG692zkNkTP4P+cKPfetftYlbjlbGaQLpf6Yuim7uUP6cXv3ziB4rxMsdrO3BZDQdA63Ap1rLbMkJ10TO9f9zUIepP8DJ3EieW8FOQhdBr3g=
---

# Pixel Toolbox IMS 注入完整经验总结

> 一份可复用的技术复盘：从问题诊断、方案设计、真机验证到经验沉淀。
> 项目：pixel-toolbox-root（com.example.pixeltoolbox）
> 设备：Pixel，Android 17 / API 37，root（Magisk / APatch / SukiSU），双卡（联通主卡 subId=2 NR + CMHK 副卡 subId=8 LTE）
> 日期：2026-08-14

---

## 目录

1. [概述](#1-概述)
2. [背景与用户诉求](#2-背景与用户诉求)
3. [问题诊断：overrideConfig 为什么失效](#3-问题诊断overrideconfig-为什么失效)
4. [方案设计：XML 直改的技术依据](#4-方案设计xml-直改的技术依据)
5. [完整实现细节](#5-完整实现细节)
6. [真机验证记录](#6-真机验证记录)
7. [成功关键点](#7-成功关键点)
8. [Agent 补充的想法与问题（用户未提及）](#8-agent-补充的想法与问题用户未提及)
9. [边界与风险](#9-边界与风险)
10. [后续优化方向](#10-后续优化方向)
11. [经验教训沉淀](#11-经验教训沉淀)

---

## 1. 概述

本次任务的核心成果：**在 Android 17 上，用「root 直改 CarrierConfig XML + killall 重载」方案，彻底绕开了被 CVE-2025-48617 封堵的 `ICarrierConfigLoader.overrideConfig()`，实现了 IMS / 5G CarrierConfig 注入的真实生效**，并完成注入 / 备份 / 还原三环节的真机闭环验证。

关键结论一句话：**IMS 按钮不再「假装成功」，而是真的写进了 telephony 的配置缓存，且 dumpsys 可复核。**

---

## 2. 背景与用户诉求

### 2.1 项目背景

- 项目是 Android root 工具类 App，核心功能之一是「信号页」的 IMS / 5G CarrierConfig 注入。
- 用户是 Android 折腾型用户，设备已 root（Magisk/APatch/SukiSU），明确「我这个版本只有 root，没有 shizuku」。
- 用户找 GitHub 项目（ryfineZ/carrier-ims-for-pixel，上游 vvb2060/Ims → Mystery00/TurboIMS）是为了**看思路**，不是要照搬 shizuku 那套。

### 2.2 用户诉求（逐条）

1. 确认「IMS 按钮是不是真的有用、真的生效」。
2. 「达到 100MHz 就显示 5G+」（5G+ 图标触发语义）。
3. 怎么优化、怎么找问题。
4. 怎么让信号更稳定、网速更好。
5. root 比 shizuku 权限高，怎么利用这个优势。
6. 最后「修改到完美」。

### 2.3 用户偏好（贯穿全程的约束）

- **先商量再解决**：改代码前必须先说根因与方案，用户确认后才动手。
- 功能开关需独立、UI 风格一致、带中文注释、回读系统状态高亮。
- 数据显示不要 `--`；不想多装 Shizuku；VoWiFi 默认关。

---

## 3. 问题诊断：overrideConfig 为什么失效

### 3.1 原方案的调用链

```
UI「应用配置」
  → RootUtils.applyCarrierConfig()
    → app_process → ims/ImsModifier.kt
      → 反射拿 ICarrierConfigLoader
      → overrideConfig(subId, bundle, persistent)
```

### 3.2 三个硬伤

**硬伤 1：persistent 参数逻辑有 bug，重启即丢**

```kotlin
try { m3.invoke(loader, sId, b, false) }   // persistent=false 先试
catch (_: Exception) { m3.invoke(loader, sId, b, true) }
```

`overrideConfig(int subId, PersistableBundle bundle, boolean persistent)` 第三参数：
- `false`：只写内存，radio 重启 / 系统重载即清空；
- `true`：持久化到 carrier config 数据库。

原代码**先试 false，只要不抛异常就结束**——结果写入的是非持久覆盖，重启即丢。UI 却提示「配置已应用」，造成「看似生效、重启就没」的假象。

**硬伤 2：没有回读验证，无法确认 telephony 是否采纳**

`overrideConfig` 不抛异常 ≠ 配置真被 telephony 采用。可能被 selinux 拦、key 被丢弃、subId 错误。原代码只看 `app_process` 退出码（`System.exit(0)` → 成功），等于没有验证。

**硬伤 3：「一键还原」清不掉 override**

`restoreCarrierConfig` 用 `settings delete` + 飞行模式开关。但 overrideConfig 写入的是 carrier config **覆盖层**，飞行模式只重启 radio，清不掉覆盖值。真正还原需要 `overrideConfig(subId, null)` 或删数据库文件。

### 3.3 深层原因：CVE-2025-48617 到底封了什么

这是本次诊断最关键的技术判断，纠正了一个常见误解：

> **CVE-2025-48617 封堵的是 shell UID(2000) 通过 `adb shell` 调 `overrideConfig` 的路径，不是 root。**

- root（UID 0）在 Android 的 **DAC 权限检查**（`checkUidPermission` 里 `uid == 0 → GRANTED`）中被放行，所以 root 直调 overrideConfig 的**非持久路径**能过；
- 但 `overrideConfig(persistent=true)` 的持久化写入，需要额外通过「system app」身份校验，**root UID 0 也过不了**（root 不等于 system app）；
- 因此 root 能写内存、写不了持久化，这解释了「硬伤 1」为什么 persistent=true 的兜底分支其实也是无效的——原代码里 `catch` 后重试 `persistent=true` 大概率同样失败。

**结论**：root 比 shizuku 权限高的优势，体现在 DAC 层；但 overrideConfig 的持久化门槛在「system app 校验」，这个门槛 root 和 shizuku 都过不了，所以正确的路不是「想办法提升权限」，而是**绕过 overrideConfig 这条 API**。

---

## 4. 方案设计：XML 直改的技术依据

### 4.1 为什么选 XML 直改

overrideConfig 被卡死，但 CarrierConfig 的最终落地是磁盘上的持久化缓存文件。与其通过 API（受权限/selinux/CVE 多重拦截）写，不如**直接改缓存文件本身**。

### 4.2 文件位置

```
/data/user_de/0/com.android.phone/files/
  carrierconfig-com.google.android.carrier-<iccid>-<carrierId>.xml
  carrierconfig-...nosim.xml
```

- `com.google.android.carrier` 是 CarrierConfig 的默认实现（Google carrier app）；
- 文件由 carrier app 生成，是 carrier config 的持久化缓存；
- telephony（phone 进程）通过 `ICarrierConfigLoader` 查询时，读的就是这个缓存。

### 4.3 关键技术障碍：root 拿不到 ICCID

按 ICCID 定位文件名是最直接的思路，但实测** root 进程经 `SubscriptionManager` 读 ICCID 会被 selinux 拦截**（root 只过 DAC，不过 selinux 的 MAC 规则）。

**对策**：不解析 ICCID，改为**遍历目录下所有 `carrierconfig-*.xml`（排除 nosim），全卡处理**。这既是绕过 selinux 的务实选择，也天然覆盖了双卡场景。

### 4.4 触发重载

改完 XML 后，phone 进程不会自动感知，需要 `killall com.android.phone` 触发 telephony 重启、重新加载缓存。

---

## 5. 完整实现细节

### 5.1 ImsModifier 重写（XML 直改核心）

改造后的 `ims/ImsModifier.kt` 逻辑：

```
main(args)
  1. 解析 args[0] = "key=value,key=value,..."  → 开关 Map
  2. args[0] == "restore" 时走还原分支
  3. 遍历 /data/user_de/0/com.android.phone/files/ 下 carrierconfig-*.xml（排除 nosim）
  4. 首次注入前：每个文件备份到 /data/local/tmp/pixeltoolbox_carrier_backup/
  5. 注入：对每个 XML 做正则/节点替换，写入开关对应 key
  6. 还原：用备份文件内容覆盖回原文件
  7. killall com.android.phone 触发重载
  8. println("SUCCESS:N") / println("RESTORED:N")  + System.exit(0)
```

关键点：
- **备份先行**：首次注入前备份，保证「一键还原」能回到注入前的原始状态（而非「官方默认」，因为原始状态里可能有运营商自己的配置）；
- **排除 nosim**：nosim.xml 无实际 SIM，注入无意义；
- **写磁盘持久**：XML 是文件，天然持久，绕开了 overrideConfig 的 persistent 门槛。

### 5.2 支持的开关 key（15 个）

| 分组 | key | 含义 |
|------|-----|------|
| A 通话类 | volte / vilte / ut / vowifi | VoLTE / 视频通话 / 补充业务 / VoWiFi |
| B 5G 核心 | nr_5g / vonr / cross_sim | 5G NR(SA+NSA) / VoNR / 跨 SIM IMS |
| C 显示增强 | lte_4g / 5g_signal / 5ga_icon | LTE 显示 4G / 信号阈值 / 5G+ 图标 |
| D 基带调优 | nr_sa_fast_camp / 5g_ca_enable / dynamic_sar / smart_data_switch | SA 快速驻网 / 5G CA / SAR 省电 / 智能数据切换 |
| E 网络引擎 | unlock_network_types | 解锁全部网络制式 |

### 5.3 5G+ 图标语义修正（关键 bug 修复）

`5g_icon_configuration_string` 的格式是 `state:icon,state:icon,...`，其中 `connected` 状态对应 **NR Advanced（聚合带宽达到阈值）**：

- `connected:5G` → 达到 100MHz 仍显示 5G（只有 `connected_mmwave` 才 5G+）
- `connected:5G_Plus` → 达到 100MHz 显示 5G+

用户要的是「100MHz 显示 5G+」，但 root 版写的是 `connected:5G`（漏改），shizuku 版已改成 `connected:5G_Plus`。本次统一改为 `connected:5G_Plus`。

### 5.4 调用链（完整闭环）

```
UI「应用配置」
  ├─ RootUtils.saveImsConfig(context, toggleMap)        存开关快照
  └─ ShizukuUtils.applyCarrierConfig()                 适配层
       └─ RootUtils.applyCarrierConfig()
            ├─ D 组 setprop（nr_sa_fast_camp / 5g_ca_enable / dynamic_sar / smart_data_switch）
            ├─ settings put / cmd phone（vonr / unlock_network_types）
            └─ app_process → ImsModifier（XML 直改 + killall）

UI「一键还原」
  ├─ RootUtils.clearImsConfig(context)                 清快照
  └─ ShizukuUtils.restoreCarrierConfig()               适配层
       └─ RootUtils.restoreCarrierConfig()
            ├─ settings delete / setprop 还原 / 制式还原
            └─ app_process → ImsModifier restore（XML 还原 + killall）

开机自启
  └─ ImsBootReceiver → hasImsConfig() 有快照 → RootUtils.applyCarrierConfig(-1) 重注入
```

> 关键澄清：`ShizukuUtils` 是**适配层**，虽然名字带 shizuku，但内部已全部重定向到 `RootUtils`，实际走纯 root 方案，无 shizuku 依赖。这是「只有 root 没有 shizuku」的实现本质。

---

## 6. 真机验证记录

### 6.1 注入

```
命令：app_process -Djava.class.path=<base.apk> /system/bin \
      com.example.pixeltoolbox.ims.ImsModifier volte=1,5ga_icon=1 -1
返回：SUCCESS:2    （2 张卡全部注入）
```

`dumpsys carrier_config` 复核（telephony 是否真采纳）：

| Key | 注入前 | 注入后 |
|-----|--------|--------|
| carrier_volte_available_bool | false | **true** |
| nr_advanced_threshold_bandwidth_khz_int | 0 | **100000** |
| 5g_icon_configuration_string | connected:5G | **connected:5G_Plus** |

> 注：dumpsys 中仍有一行显示 `false`/`0`/`connected:5G`，那是 **nosim 默认配置**，未注入，符合预期。

### 6.2 备份

```
/data/local/tmp/pixeltoolbox_carrier_backup/
  carrierconfig-...89353330626007021561-767.xml   10432 B
  carrierconfig-...89860126407450065674-1436.xml  10786 B
```

两卡原始 XML 完整备份，大小与原文件一致。

### 6.3 还原

```
命令：app_process ... ImsModifier restore -1
返回：RESTORED:2
```

- 主卡：注入 key（volte / 5g_icon / nr_advanced_threshold / pixel_toolbox_config_version）全部消失，还原干净；
- 副卡（eSIM）：`carrier_volte_available_bool=true` 与备份原件一致——该卡原始配置本就开启 VoLTE，还原正确（不是残留）。

---

## 7. 成功关键点

1. **纠正了对 CVE 的误判**：认清了「封的是 shell 不是 root」，从而不再纠结于「提升权限」，转而「绕过 API」。
2. **从「写 API」转向「写文件」**：CarrierConfig 的最终落地是磁盘缓存，直接改文件绕过了权限/selinux/CVE 的多重拦截。
3. **selinux 障碍的务实绕过**：root 读不到 ICCID，就用「遍历全卡」替代「按 ICCID 定位」。
4. **验证闭环**：不只信 app_process 退出码，用 `dumpsys carrier_config` 复核 telephony 是否真采纳。
5. **备份先行**：注入前备份，还原才「回得到原始状态」而非「猜官方默认」。

---

## 8. Agent 补充的想法与问题（用户未提及）

以下问题是分析过程中发现、但用户没有明确提出的，属于本次经验的「隐藏知识点」。

### 8.1 root ≠ 绕过 selinux

这是最容易误解的一点。root（UID 0）只过 **DAC**（权限位检查），但 selinux 是 **MAC**（强制访问控制），root 的 `app_process` 进程仍受 selinux domain 约束。实际表现：
- root 直调 overrideConfig 非持久路径能过（DAC 放行）；
- 但 root 经 `SubscriptionManager` 读 ICCID 被 selinux 拦（MAC 拒绝）。

**教训**：判断「root 能不能干某事」，不能只想到 UID 0，还要问「这个操作过不过 selinux」。

### 8.2 无回读验证 = 自欺

`app_process` 退出码 0 只代表「没抛异常」，不代表「telephony 采纳」。任何注入类操作，**必须有一个独立的、可复核的回读手段**（这里是 `dumpsys carrier_config`）。否则就是在「假装成功」。

### 8.3 飞行模式清不掉 override

「一键还原」用飞行模式 + `settings delete`，但对 overrideConfig 写入的 carrier config 覆盖层无效（飞行模式只重启 radio）。这是原还原逻辑的隐性 bug。

### 8.4 网速/信号的「有用 vs 玄学」辨析

root 能撬动的网速/信号优化，远少于直觉预期：

| 操作 | 真实效果 | 判断 |
|------|---------|------|
| 5G CA（5g_ca_enable + CA 相关 key） | 运营商部署了 CA 才翻倍 | **值得开，需 dumpsys 确认聚合** |
| SA 快速驻网（nr_sa_fast_camp） | 加速 SA 驻网，移动中更稳 | 值得开 |
| 掉网自动重连 | 掉网快速恢复 | 有实际价值 |
| 锁定制式（NR/LTE） | 减少乒乓切换 | 有价值，但别一票全开 |
| 改 TCP 缓冲 / BBR | 空口是瓶颈，TCP 早不是 | **玄学，别折腾** |
| dynamic_sar=0 关 SAR | 短期抗降频，违反合规+费电 | 不建议 |
| 5G 信号阈值 / 5G+ 图标阈值 | 只改状态栏显示，不动真实信号 | **纯显示自欺** |

**核心事实**：网速和稳定性由空口质量、运营商配置、modem 固件决定，root 能撬动的只有制式锁定、CA 开关、掉网重连这几项。图标/阈值类 key 是「看起来调优了」，实际网速没变。

### 8.5 XML 持久性 vs carrier app 重新生成的博弈

XML 文件是 carrier app（com.google.android.carrier）生成的缓存。整机重启后，carrier app 有可能**重新生成 XML 覆盖我们的注入值**。这是「写磁盘持久」之外的一个不确定因素，尚未做整机重启实测。开机重注入（ImsBootReceiver）是应对此风险的双保险。

### 8.6 全卡 vs 按卡的权衡

root 拿不到 ICCID，全卡处理是合理默认；但 UI 的「目标 SIM ID」选择在目前实现下**形同虚设**（subId 参数被忽略）。若需真正的按卡注入，要用 root 命令取 subId→ICCID 映射（如解析 `dumpsys isub`），再定位对应文件。

### 8.7 killall 的副作用与更优替代

`killall com.android.phone` 会全量重启 phone 进程，可能打断进行中的通话/数据。更优方案是 `ITelephony.resetIms(slotIndex)` 精确重启 IMS（GitHub ImsResetter 的思路），不打断数据业务。

### 8.8 备份位置的风险

备份放在 `/data/local/tmp/pixeltoolbox_carrier_backup/`，该目录**不在系统备份范围**，若用户清理 tmp 分区，备份会丢失，届时「一键还原」会因找不到备份而失效。可考虑存到 App 私有目录或 SD 卡。

### 8.9 双版本配置不一致的历史隐患

项目曾并存 root 版（`ims/ImsModifier.kt`）和 shizuku 版（`shizuku/ImsModifier.kt`），两版配置项一度不一致（5G+ 图标语义、CA/DC key）。**改配置只改了一份**是典型风险。本次虽定位到 root 版为唯一主力，但 shizuku 三件套仍是死代码，需清理。

### 8.10 死代码

`shizuku/ImsModifier.kt`、`shizuku/ImsConfigServiceImpl.kt`、`shizuku/ConfigReaderInstrumentation.kt` 已无引用（ShizukuUtils 全部重定向到 RootUtils），可安全删除，减少后续维护心智负担。

### 8.11 mount namespace 隔离：App 内注入失败的真正根因

本次排查最隐蔽的一个坑，现象极具迷惑性：

- **现象**：命令行手动 `su -c "app_process ... ImsModifier volte=1 ..."` 注入返回 SUCCESS:2；但 App 内点「应用配置」却报 `ERROR: no carrier config xml found`，`filesDir exists=false`。
- **根因**：Magisk su 默认在**调用方（App）的 mount namespace** 里执行命令。App 进程与 phone 进程的 mount namespace 不同，App 看不到 `/data/user_de/0/com.android.phone/files/` 目录，自然列不出 carrier config XML。
- **验证**：`su -t <AppPID> -c ls` 不可见，`su -t <AppPID> -c "su --mount-master -c ls"` 可见。
- **修复**：`RootUtils.executeCommand / executeCommandVerbose` 的 su 调用统一加 `--mount-master`，让命令跑在全局 namespace。
- **教训**：root 命令「命令行好使、App 里不好使」，先怀疑 mount namespace，而非权限。

### 8.12 按钮真实有效性盘点

15 个开关按「真实有效 / 有条件 / 玄学」分三档：

| 档位 | 开关 | 说明 |
|------|------|------|
| ✅ 真实有效 | volte / vilte / ut / vowifi / vonr / cross_sim / lte_4g / unlock_network_types | 写进 telephony 或系统设置，dumpsys 可复核 |
| ✅ 显示有效 | 5ga_icon | 100MHz→5G+ 图标，已验证生效 |
| ⚠️ 有条件 | nr_5g / nr_sa_fast_camp / 5g_ca_enable | 声明有效，实际取决于运营商部署 CA/SA |
| ❌ 玄学 | 5g_signal / dynamic_sar / smart_data_switch | 只改显示阈值或 vendor 不读的 prop |

**单独 / 组合注入结论**：可以任意组合，UI 只开想要的开关、点「应用配置」即只注入对应 key。但分两类行为——D 组 setprop（4 个）开写 1、关写反向值，开=生效、关=撤销；A/B/C 组 XML（10 个）+ vonr + unlock_network_types（11 个）是**单向注入**，关掉不会撤销残留值。想干净地只留某几个开关，正确顺序是「一键还原 → 重新只开想开的 → 应用配置」。

---

## 9. 边界与风险

1. **整机重启持久性未实测**：XML 写磁盘 + 开机重注入是双保险，但 carrier app 重启是否重新生成 XML 覆盖，需真机重启后 `dumpsys carrier_config` 复核一次。
2. **全卡注入**：当前不区分 SIM 卡，双卡同时注入。对 IMS/VoLTE/5G 这类「全局能力」通常合理，但不支持单卡差异化。
3. **killall 打断业务**：注入/还原会短暂重启 phone 进程，若正通话/传输可能中断。
4. **备份依赖 tmp 分区**：备份位置不在系统备份范围。
5. **D 组 setprop 部分需 radio 重启**：setprop 写入后，部分 prop（如 5g_ca_enable）需 radio 重启才被 modem 读取，非即时生效。

---

## 10. 后续优化方向

1. 清理死代码（shizuku 三件套）。
2. 用 `ITelephony.resetIms(slotIndex)` 替代 killall 全量重启。
3. 按卡注入：root 命令取 subId→ICCID 映射，实现「目标 SIM ID」真实生效。
4. 备份迁移到持久位置。
5. 注入后增加 `dumpsys carrier_config` 自动回读并展示给用户（把「真生效」透明化）。
6. 整机重启持久性实测。

---

## 11. 经验教训沉淀

1. **先定位「门槛在哪一层」再决定策略**：overrideConfig 的持久化门槛在「system app 校验」，root 和 shizuku 都过不了，所以提升权限是死路，绕过 API 才是活路。
2. **「写 API」和「写文件」是两条不同的路**：API 受权限/selinux/CVE 多重拦截时，回到数据的最终落盘形态（文件）往往更直接。
3. **root 的边界 = DAC 过 + selinux 未必过**：凡是「root 读不到/写不进」的诡异现象，先怀疑 selinux。
4. **注入类操作必须闭环验证**：写入 → 回读 → 确认 telephony 采纳，缺一不可，否则就是自欺。
5. **备份先行，还原才能回原**：还原的目标是「注入前的原始状态」，不是「猜一个默认值」。
6. **改动前先商量**：结构性改动先给根因+方案，用户确认再动手，能少返工。

---

*本文档由 Marvis 在 pixel-toolbox-root IMS 注入改造任务中整理，供后续复用。*
