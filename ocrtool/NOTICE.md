# MuOCR third-party notes

MuOCR is a small Android OCR utility developed inside the MuNote repository.

The large-PDF strategy and PDF text-layer implementation were informed by public open-source projects:

- OCRmyPDF (MPL-2.0): https://github.com/ocrmypdf/OCRmyPDF
- MyPDF (Apache-2.0): https://github.com/g-o-d-v/MyPDF
- ProPDF Editor (MIT): https://github.com/aranmanaikkollai-hue/proPDFEditor
- PDF Toolkit (Apache-2.0): https://github.com/Karna14314/Pdf_Tools

MuOCR uses PDFBox-Android and Google ML Kit Chinese text recognition as runtime dependencies.
The implementation in this module is written for MuOCR and keeps source PDF page content intact while appending an invisible OCR text layer.
