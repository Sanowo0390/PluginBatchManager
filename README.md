# PluginBatchManager

Paper/Purpur と Velocity に対応するプラグイン一括更新ツール（試作版）。

- 配布元の JAR ファイル名を維持
- 旧 JAR は `plugins/.disabled/<batch>/` に移動
- `/pbm`（Paper/Purpur）、`/pbmv`（Velocity）で更新を実行

## GitHub Actions からダウンロード

[Actions](https://github.com/Sanowo0390/PluginBatchManager/actions) → **Build PluginBatchManager** → 成功した実行 → **Artifacts** から **PluginBatchManager-Paper** または **PluginBatchManager-Velocity** を選択。ZIP を解凍して JAR を入手できます。

> **注意:** 現段階は試作版です。公式 API でのビルド、実サーバー起動、配布元からのダウンロードが確認できるまで本番には導入しないでください。

詳細: [README_ja.md](README_ja.md)
