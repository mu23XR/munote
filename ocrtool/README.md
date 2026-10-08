# MuOCR

MuOCR 是 MuNote 仓库里的独立 Android OCR 工具，用于把大型扫描 PDF 转成可搜索 PDF。

## 目标

- 直接导入现有 PDF；
- 不自动裁边、不重建可见页面；
- 中文 + 英文 OCR；
- 把 OCR 结果作为不可见文字层追加到原 PDF 页面；
- 逐页渲染、逐页 OCR，避免把整本 PDF 解码进内存；
- PDFBox 使用磁盘 scratch，优先降低超大 PDF 的 Java heap 压力；
- 长任务通过 WorkManager 前台任务运行，可切到后台。

## 使用

1. 安装 MuOCR APK。
2. 选择原扫描 PDF。
3. 1GB 级大文档先选“平衡（2200 px）”。
4. 点击“开始 OCR 并导出 PDF”，选择输出文件。
5. 保持设备有足够可用存储，建议接电。

## 与普通扫描器的区别

MuOCR 不把导入 PDF 的页面先裁剪成“扫描图片”再生成新 PDF。

处理流程：

```text
原 PDF
  -> 单页渲染（仅供 OCR）
  -> 中文 OCR
  -> OCR 行文字 + 坐标
  -> 在原 PDF 对应页面追加不可见文字层
  -> 导出
```

原页面内容本身保持不动。

## 大文件策略

- 一次只渲染一页；
- OCR 位图限制在约 4.5MP；
- PDFBox 使用 `MemoryUsageSetting.setupTempFileOnly()`；
- OCR 结果立即写入当前页面，不把整本 OCR 结果保存在内存；
- 输出阶段直接写入用户选择的目标文件。

这能显著降低 1GB 级扫描 PDF 的峰值内存，但最终上限仍取决于 PDF 结构、系统可用存储、Android 文档提供器和 PDFBox 对特定文件的兼容性。

## License / notices

MuOCR 作为 MuNote 仓库的一部分发布。第三方参考和依赖说明见 [NOTICE.md](NOTICE.md)。
