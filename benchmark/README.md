# 固定集与设备性能记录

`src/main/resources/accuracy.tsv` 是 **validation-only** 的 24 条原创短句。上下文回归案例可能和原创词条重叠，其余短句验证一般字符/形态数据、假名、地区与混合语言行为；不把该集合称为独立大规模准确率评测。读音正确性比较忽略格式化空格，结构正确性另外比较整个 `ParseResult`。新增评测样本不能被准备脚本或编译器自动导入词典。

JVM 集成测试还验证缓存 0/1/4/256、共享引擎配独立工作区并发、完整来源声明、汉字语言歧义。公共单元测试验证 UTF-16 原文位置、补充平面汉字、日语长音/促音/拗音/拨音/半角/组合浊点、逐字投影及无法对齐的词级返回、异常释放工作区。编译器测试覆盖块边界、输入顺序不影响二进制、去重、只读取头部、矩阵符号/方向和用途/哈希/路径拒绝。

CP/McB 新增校验包括来源 ID 及地区准入、重复来源合并、专名跨 emoji 后的 UTF-16 对齐、McB 的「行行」未解决异读、「一／不」本调规范化、注音轻声与阴平区别、连写拼音的歧义切分拒绝。sample 测试验证「沃兹沃思」「樂器」读音投影回原时间音节后保留内容、时间和翻译。这些校验也不作为构建期词典输入。

## 常见多音词覆盖回归（2026-10-06）

`src/main/resources/mandarin-polyphones.tsv` 为 195 项人工词义/地区读音回归案例（CN 126、TW 69），明确与本次纳入的 Apache-2.0 自有词库重叠，**不是独立测试集或真实歌词准确率**。解析仍读取编译语言包，构建器不会读取此文件。每个案例比较数字声调及缓存 0/256 的完整解析结构，JSON 保留原文区间、候选、成本差、来源和输入/包哈希。

原始结果：[补充前](results/mandarin-polyphones-before.json)、[补充后](results/mandarin-polyphones-after.json)。CN 首选词典读音匹配数 67/126 → 126/126；59 项原有缺口均因整词未覆盖、退回 Unihan 单字选择。TW 为 69/69 → 69/69。初始测试误将台湾「朝夕」「湖泊」填写为大陆音，核对地区读音后已纠正标注；前后报告都用同一纠正后的输入，不把标注纠正算作解析器改进。

自有词条不会删除上游异读。「着」单独仍有歧义，台湾「著涼」的另一候选仍保留；候选成本不是概率。相同读音合并后保留多个来源，例如「樂器」同时保留 McBopomofo 和自有来源。sample 验证「为你着迷」跨时间音节仍使用整行上下文，同时保留原文、翻译及时间，Latin 文本仍不生成字幕。

CN 编译包为 5,256,476 → 5,260,906 bytes（+4,430），TW 为 7,433,712 → 7,433,815 bytes（+103）；TW 大部分整词原已覆盖，本次主要追加来源声明和成本。此回归没有目标设备性能成绩，下面 CP/McB 和初版设备记录仍对应各自旧包哈希。

```powershell
.\gradlew.bat :benchmark:run '--args=--audit src/main/resources/mandarin-polyphones.tsv results/mandarin-polyphones-after.json'
# 查看实际分词、候选及来源，不只查看格式化结果：
.\gradlew.bat :benchmark:run '--args=--inspect zh-CN 着迷 看着 睡着'
```

## 网上追加候选检查（2026-10-06）

在上述自有词库发布后，另选取 86 项校验候选（CN 80、TW 6），与 195 项自有回归集无重叠。候选来自教育主管部门和高校的读音教学资料，逐项来源及准入边界见 [校验来源登记](src/main/resources/mandarin-web-candidates.sources.json)，完整输入见 [候选 TSV](src/main/resources/mandarin-web-candidates.tsv)，结果见 [实际编译包诊断报告](results/mandarin-web-candidates.json)。**这批是 validation-only，没有纳入自有词库或发布词典，不是一般歌词准确率评测。** 外部网页没有明确数据再分发许可，台湾教育部辞典有 BY-ND 条件；不能靠本项目的 Apache 许可重新授权它们。

检查直接查询编译包：`wordEntries` 表示整词实际候选，`spans` 表示引擎选出的分词及读音。CN 80 项均无整词条目，60 项首选词典读音不匹配（包括多音及轻声），20 项虽单字回退读对，仍缺整词上下文。TW 6 项均有 McBopomofo 整词；「捨得」「空白」有正确候选，但与另一读音同为成本 200，当前首选不符合该校验读音。两种情况分别是覆盖缺失和未解决的候选排序，不能混为词条未收录。

歌词相关优先缺口示例：

| 地区／词语 | 当前首选 | 校验读音 |
| --- | --- | --- |
| CN 倔强 | jue2 qiang2 | jue2 jiang4 |
| CN 模样 | mo2 yang4 | mu2 yang4 |
| CN 宁愿 | ning2 yuan4 | ning4 yuan4 |
| CN 勉强 | mian3 qiang2 | mian3 qiang3 |
| CN 懂得 | dong3 de2 | dong3 de5 |
| CN 暖和 | nuan3 he2 | nuan3 huo5 |
| CN 漂泊 | piao4 po1 | piao1 bo2 |
| CN 附和 | fu4 he2 | fu4 he4 |
| CN 折腾 | zhe2 teng2 | zhe1 teng5 |
| CN 慰藉 | wei4 ji2 | wei4 jie4 |
| TW 捨得 | she3 de2 | she3 de5 |
| TW 空白 | kong1 bai2 | kong4 bai2 |

CN「模样」与 TW「模樣」不可统一为同一个音；台湾 `mo2 yang4` 在本轮正确。查核依据包括 [PRC 教育部 GF 0015—2010「倔强」](https://www.moe.gov.cn/jyb_sjzl/ziliao/A19/201010/W020220124393643101738.pdf)、[南京审计大学多音字教学说明](https://wxy.nau.edu.cn/2014/0309/c779a6452/page.htm)、[山东医学高等专科学校教学资料](https://jnwyjxb.sdmc.edu.cn/info/1068/1370.htm)、[上海市教委审音表](https://edu.sh.gov.cn/yywz_gfbz_gypth/20150712/0015-yywz_1999.html)、台湾教育部 [捨得](https://dict.concised.moe.edu.tw/dictView.jsp?ID=33924&la=0&powerMode=0)、[空白](https://dict.concised.moe.edu.tw/searchR.jsp?ID=116&ID=3&ID=5&la=0&powerMode=0&word=%E7%A9%BA)、[模樣](https://dict.concised.moe.edu.tw/dictView.jsp?ID=3667)。教学资料可能有排版和标注错误；只引用核对过的事实，不把整页转换为词库。

报告对应 CN `23c2d2ce9d60134996ac083398252b267851317ab5339f0a9297e9a6438694c2`、TW `c686f8750a0f49f3b3c9a2fa5b8e48e3fbae6cd6a9526dbdf8763c983bb65587`。缓存 0/256 的完整结果逐项一致；此检查没有改动发布包。

```powershell
.\gradlew.bat :benchmark:run '--args=--audit src/main/resources/mandarin-web-candidates.tsv results/mandarin-web-candidates.json'
```

## 日语外部注音语料校验（2026-10-07）

以相同的 5,582 个完整句子比较 0.1.6 和 0.1.8，结果见 [当前报告](results/japanese-corpus.json)，`comparison` 保留 0.1.6 的版本、代码／预测哈希及逐句对照。[初始 0.1.3](results/japanese-corpus-before.json) 保留早期基线。诊断参与过修正，属于开发集反馈，不是独立歌词准确率。下载、抽样、评分统一在 [japanese.py](japanese.py)；原始语料和逐句预测／诊断只存于忽略的 `build/japanese-validation/`，不发布或导入词典。

- [KWDLC 京都大学 Web 文档语料](https://github.com/ku-nlp/KWDLC/blob/c6ae49d29eca4e1134c6676d682adf5ed6a25f5a/README.md)：JUMAN/KNP 标注经人工修正，使用官方测试文档中的 2,159 个含汉字句；上游未取得原始 Web 文本的再分发许可，仅用于本地研究校验。
- [NDL 青空文库／点字注音语料](https://github.com/ndl-lab/huriganacorpus-aozora)：固定 SHA-256 种子选 80 位作者、每作者最多 2 本、每书最多 25 句，得到 140 本、3,423 句；筛选不参考预测。[No Copyright 声明](https://github.com/ndl-lab/huriganacorpus-aozora/blob/c20b60bc2a6fcdfd3964b71ed9e3a46c6e330e8d/LICENSE) 仍有司法辖区／人格权限制，本项目只作本地校验。其候选生成包含 IPA，不完全独立于本库的 IPADIC；旧字及标注噪声影响分数。

| 语料 | 含汉字读音组匹配，0.1.6 → 0.1.8 | 整句日文读音匹配，0.1.6 → 0.1.8 |
| --- | ---: | ---: |
| KWDLC | 12,154 → 12,180 / 12,759（95.26% → 95.46%） | 1,634 → 1,657 / 2,148（76.07% → 77.14%） |
| NDL 青空文库 | 20,521 / 23,004（89.21%，不变） | 1,316 / 2,956（44.52%，不变） |

KWDLC 逐句新增 36 个完全匹配句，13 个从完全匹配变成不匹配；NDL 两项均为 0。检查这 13 个句子后，新增差异均来自完整数字计数读法：7年／7人 的 `しち／なな`、20歳 的 `はたち／にじゅっさい`、0 的 `れい／ゼロ`、2009年 的 `きゅう／く`，以及参考分词未体现 38本／第8回 的促音音变。合法异读仍计作与指定参考不匹配，不能通过改参考或强制选择基本读法提高分数。常见合法替代读音保留为候选；词典复合词可能具有不同成本，不把所有候选伪装成等概率。

评分按参考／预测的共同 UTF-16 边界合并最小读音组，不强求分词一致或猜测逐字对齐。比较原生假名，统一全半角、平片假名及长音的对应元音；KWDLC 助词接受书写／发音标注，同时报告严格计数。纯 Latin／数字／标点组不评分，数字与汉字量词合为读音组时按整个计数结构评分。无法处理的参考排除所在整句（KWDLC 11、NDL 467）；整句分母固定，分词改变可能影响组分母。

0.1.8 补充整词数字语法、词性／连体形相邻评分以及去除弱偏好的完整句子歧义成本。`学生の方` 的弱偏好仍可选 `かた`，但 `ほう` 的原有竞争读音不被加分抹掉；比较句「高い方／書く方」不套用人称规则。KWDLC 与参考不符而被标为 `RESOLVED` 的汉字组为 547 → 525，仍存在大量未揭示的错误，不能宣称已完成一般消歧。低于阈值的候选与分词边界同时保留，成本不是置信概率。

四个编译 `.lpd` 文件字节不变；新增 IPADIC 派生语法元数据保留上游授权，详见 [数据说明](../docs/DATA.md)。全部 5,582 句的缓存 0、复用 256、立即热态 256 产生相同完整 `ParseResult`，输出均为 ASCII。最后的加载／工作区／歧义计算性能调整没有改变任一句的记录读音、候选或成本；原始完整结构也继续通过缓存一致性验证。

同一 24 条固定集、Windows/JDK 21、独立 JVM 和 mmap 的 0.1.6／0.1.8 观测见 [前](results/jvm-japanese-before.json)、[后](results/jvm-japanese-after.json)，保留实际 JAR／数据哈希，两版均 24/24。日语热态中位数 18.8 → 25.8 μs、P95 50.8 → 62.0 μs；进程首个 CN 调用 27.56 → 25.40 ms，四语言工作负载后的 heap 44,240,448 → 46,996,176 bytes。每地区另一个实例的首次调用已受同进程初始化影响，不是该地区独立进程冷启动。新增评分有代价，且后台构建／JIT／GC 未固定；不能据单次桌面观测宣称稳定提速或持久内存变化。最新 Android 实测见下节；iOS 尚无运行成绩。

```powershell
python benchmark/japanese.py
python benchmark/japanese.py --score-only
# 前后比较必须同时给出基线报告及匹配其哈希的本地预测文件：
python benchmark/japanese.py --score-only --baseline-report <report.json> --baseline-predictions <predictions.jsonl>
```

## 四语言未调参句子与数字回归（2026-10-07）

[src/main/resources/lyrics-holdout.tsv](src/main/resources/lyrics-holdout.tsv) 包含 34 条原创歌词式完整句子：每语种 8 条及 2 条显式标签的混合行，在本轮运行预测前自编、自标注并冻结；该输入及预测没有用于本轮调参。唯一预运行修正是混合行标签的 UTF-16 长度。它不是真实歌曲语料、独立人工盲标或具有代表性的统计抽样。最终性能调整后，所有案例的完整记录候选／成本与第一次预测一致。

[最终报告](results/lyrics-holdout.json) 记录库版本、策略代码／输入／数据哈希，检查缓存关闭、复用及热态结构一致。严格逐句标注匹配为 CN 8/9、TW 7/8、HK 3/8、JA 9/9（混合行归入其默认地区）。这些数值是与冻结自标注的匹配率，轻声与地区异读尚需人工复核，例如 CN「闭上／眼睛」、TW「我們」；不自动修改标签提高分数，也不直接把每个差异判成解析错误。粤语的「會」等首选读音仍明显需要上下文改进。现有源词表的替代候选、成本及原文位置均保留，评测不进入发布词典。

`NumberIntegrationTest` 独立覆盖三地区整数、全角数字、逐位年份／数量时长的候选与上下文、小数、中文负号、零位插入、二／两／兩，以及日语人数／日期／分钟音变、合法异读和完整复合词；另检查编号／千分位／斜杠日期等不支持形式不被部分解析、混合语言边界、缓存结果及不可伪造的逐字投影。原来的 24 条性能固定集及其预期没有改动。

sample 的 15 项测试使用本地 Maven 0.1.8 的发布资源，验证数字、按范围指定读音、已有文件读音优先、混合行、投影分隔符，以及逐词无法对齐时主行 fallback 保留原时间音节。Android arm64-v8a／armeabi-v7a debug APK 均构建并检查真实资产、原始授权及未压缩词典。

```powershell
.\gradlew.bat :benchmark:run '--args=--audit src/main/resources/lyrics-holdout.tsv results/lyrics-holdout.json'
```

## 最新 Android 设备比较与加载诊断（2026-10-07）

[统一报告](results/android-latest.json) 保留 Xiaomi 2211133C、arm64-v8a、Android 16/API 36 的实际 OS 指纹、0.1.6／0.1.8 APK／代码／语料／数据哈希。同一 24 条固定集、同一数据字节、debug 构建，每语种与缓存 0/256 分别启动新进程。包括两轮 0.1.6 基线、最终 0.1.8 常规运行及独立加载诊断；每轮全部通过，缓存间签名一致，新版常规／诊断模式签名也一致。两轮基线已显示明显波动，页缓存、ART/JIT、温度和后台负载未控制。

下表以第二轮基线比较新版正常进程；每次 50 轮预热、300 次单行热态采样。首次调用不包括引擎注册时间，包含按需映射／读头和解析，不含格式化。

| 地区 | 首次 ms，0.1.6 → 0.1.8 | 热态中位数 μs | 热态 P95 μs | 工作负载后 PSS KiB |
| --- | ---: | ---: | ---: | ---: |
| CN | 13.54 → 14.99 | 188.5 → 305.6 | 572.1 → 777.1 | 59,644 → 62,367 |
| TW | 9.76 → 10.27 | 526.4 → 562.2 | 777.9 → 914.5 | 39,545 → 40,515 |
| HK | 8.91 → 10.60 | 487.9 → 558.0 | 549.0 → 661.5 | 38,580 → 39,533 |
| JA | 17.06 → 10.66 | 181.0 → 224.5 | 530.0 → 646.4 | 39,559 → 40,877 |

当前新增评分有热态成本，不能称为整体提速。数字／语法规则表按需初始化；没有弱偏好的句子复用正常路径成本，工作区只清理实际长度，避免曾解析长文后每行清理全部历史容量。性能优化前后语料预测／候选一致，不能以删候选换取速度。PSS/heap/RSS 是整个 instrumentation 进程的观测，含临时结果、工作区和 GC，不能当作词典持久占用或用微小变化宣称节省内存。

`--phases` 在另一组新进程分别记录引擎注册、共享映射、单独头部构造、使用已映射数据的 lazy parse 和格式化。缓存 256 的映射为 0.65–0.90 ms、头部探测 0.24–0.25 ms；其后解析仍包含 lazy 初始化及第二次读头。该模式改变类初始化顺序并已预热部分状态，阶段耗时不能简单相加、替代正常首次解析，或把诊断模式的后续 `firstParseNs` 当冷启动。iOS 两个目标的 KLIB／资源已构建并检查授权，但 Windows 无法运行 iOS 设备／模拟器，没有虚构性能数值。

```powershell
.\gradlew.bat :device-benchmark:assembleDebug
python benchmark/run_device.py --serial <serial> --output benchmark/build/device-after
python benchmark/run_device.py --serial <serial> --output benchmark/build/device-phases --phases
# 可明确选择保留的旧测量 APK；不改写当前构建产物：
python benchmark/run_device.py --serial <serial> --apk <baseline.apk> --output benchmark/build/device-before
```

## CP/McB 接入前后 JVM 观测（2026-10-06）

原始结果：[接入前](results/jvm-cp-mcb-before.json)、[接入后](results/jvm-cp-mcb-after.json)。同一 Windows 11 amd64、JDK 21.0.9、运行时及 24 条固定集，分别启动新的 JVM，使用文件 mmap，缓存 256，50 轮预热与 300 次热态采样；两次均 24/24 通过并检查缓存关闭时的完整解析结构。JSON 保留每个包和评测集的 SHA-256。

| 地区 | bytes，前 → 后 | 热态中位数 μs，前 → 后 | 热态 P95 μs，前 → 后 |
| --- | ---: | ---: | ---: |
| zh-CN | 1,463,939 → 5,256,476 | 32.5 → 29.7 | 105.2 → 84.6 |
| zh-TW | 1,464,085 → 7,433,712 | 16.3 → 18.4 | 33.1 → 28.6 |

进程首次 CN 解析为 26.96 → 28.20 ms（JSON 顶层 `firstParseNs`）。JSON 各地区 `processFirstParseNs` 实际是另一个解析器实例的第一次调用，CN 此时已被顶层测量预热，其他语言共享同一 JVM 的初始化/JIT 状态；**不能将该字段解释为每地区独立进程冷启动**。

全部四语言工作负载后的 Java heap 观测为 43,411,944 → 42,823,056 bytes；它包括瞬时结果、GC 与测量开销，不包括全部 mmap 页，不是每词典持久内存或 RSS。文件系统页缓存未清空，且只有一次前后观测，**不能据这些数字宣称稳定提速或降低内存**。Android/iOS 需各自目标设备重测。

可用相同程序对显式保留的旧、新 `.lpd` 目录测量，避免切换或重写当前发布包：

```powershell
.\gradlew.bat :benchmark:run --args="report.json E:/path/to/pack-directory"
```

## 初版设备记录（CP/McB 接入前）

`results/Xiaomi-*.json` 记录 Xiaomi 2211133C（arm64-v8a、Android 16 / API 36）的 8 次独立进程运行，分别测试四个地区与缓存 0/256。所有 24 个案例通过；每个地区的两种缓存策略得到相同的确定性 SHA-256 结果签名，各次运行内也以缓存关闭的解析结果进行完整结构比较。

这批 Android 记录对应 JSON 中的旧包哈希，CN/TW 约 1.46 MB；**不是接入 CP/McB 后的性能成绩**。新包体积约为 CN 5.26 MB、TW 7.43 MB。最新版本已另行在同一设备复测，见上节；不以初版或桌面数据代替当前 Android/iOS 成绩。

| 语言 | 文件 bytes | 首次解析 ms，缓存 256 | 热态中位数 ms，缓存 256 | 热态 P95 ms，缓存 256 |
| --- | ---: | ---: | ---: | ---: |
| zh-CN | 1,463,939 | 13.52 | 0.376 | 1.101 |
| zh-TW | 1,464,085 | 20.28 | 0.880 | 1.487 |
| yue-HK | 5,552,519 | 14.79 | 0.797 | 0.900 |
| ja-JP | 17,828,373 | 14.20 | 0.228 | 1.637 |

这些是 **debug 构建**、50 轮预热后 300 次单行解析的观测值。首次解析包含按需 mmap/头读取、lattice 和读音解析，进程是新的，但文件系统页缓存未清空。各次运行的 ART 编译、GC、温度及其他工作负载可能不同，不能由单次数据断言稳定性能提升或作为 release SLA。

内存 JSON 同时记录运行前后的 PSS、Java heap、native heap、`/proc/self/status` 的 RSS/HWM。这些是测量进程的观测值，包含 instrumentation、工作区、瞬时结果和 GC 效应，不能当作单独词典的持久占用。比较持久内存需要增加稳定点、对象保留分析及重复运行；优化必须仍然保持解析结果一致。

测试应用包名 `com.mocharealm.accompanist.phonetics.benchmark` 独立于 sample；不启动 Activity 或修改 sample 的文件/设置。运行脚本显式指定 adb serial，重新启动每个测量进程，保存设备系统构建指纹和时间。没有可用设备时不生成虚拟成绩。

```powershell
.\gradlew.bat :device-benchmark:assembleDebug
python benchmark/run_device.py --adb C:/Environments/Android/SDK/platform-tools/adb.exe --serial <serial>
.\gradlew.bat :benchmark:run
```

今后发布比较应固定已审查的数据哈希、评测集版本、目标设备/OS、构建模式、温度、预热和采样策略，同时报告正确率、未知/歧义比例、体积、首次延迟、热态耗时及内存。iOS 应在 macOS/iOS 上建立对应测量，当前只有跨平台编译验证，没有 iOS 运行成绩。
