Optional local Content Filtering models live in this directory.

Expected files:
  image_safety_xs.onnx    - local NSFW / NSFL(gore) / SFW image classifier
  toxic_minilm_int8.onnx  - aggressive/toxic text classifier
  vocab.txt               - BERT WordPiece vocabulary for the text classifier

Run download_content_filter_models.bat / .ps1 / .sh from the project root to fetch them.
The models are not included in the source archive so the Weave source package stays compact.
