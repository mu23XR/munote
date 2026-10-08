# MuOCR third-party notes

MuOCR is a small Android OCR utility developed inside the MuNote repository.

The large-PDF strategy and PDF text-layer implementation were informed by public open-source projects:

- OCRmyPDF (MPL-2.0): https://github.com/ocrmypdf/OCRmyPDF
- MyPDF (Apache-2.0): https://github.com/g-o-d-v/MyPDF
- ProPDF Editor (MIT): https://github.com/aranmanaikkollai-hue/proPDFEditor
- PDF Toolkit (Apache-2.0): https://github.com/Karna14314/Pdf_Tools

MuOCR uses PDFBox-Android and Google ML Kit Chinese text recognition as runtime dependencies.
The implementation in this module is written for MuOCR and keeps source PDF page content intact while appending an invisible OCR text layer.

## Bundled font (MuOCR 0.1.3+)

MuOCR includes the unmodified Noto Sans SC TrueType from Google's font repository under the SIL Open Font License 1.1 (OFL-1.1). It is embedded in the APK for Chinese searchable-PDF generation; downloaded during build from a pinned upstream commit and checked against the Git blob SHA. The font remains separately licensed, not under MuNote's MIT license.

- Source: https://github.com/google/fonts/tree/2eb0b48d5f760f62e286216f0859a8c540dbc1bd/ofl/notosanssc
- Original blob SHA-1: `fb0637bafbcd804fe32152370a1225990745b4bc`
- Font copyright: Copyright 2014-2021 Adobe; Reserved Font Name `Source`.
- OFL text bundled in APK: `ocrtool/src/main/assets/fonts/OFL.txt`.
