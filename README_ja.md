# PluginBatchManager 0.1.0 (試作版)

Paper/Purpur と Velocity 用の **別々のJAR**、共通の更新エンジンです。サーバー内の `plugins/` を個別に管理します。

> **重要:** 実サーバーでの稼働および実ネットワークのダウンロードは未検証です。オフラインで Java 21 を使って仮のAPI型とコンパイルし、テスト用JARで退避・復元ロジックを検証しました。いきなり本番に導入せず、サーバーと `plugins/` 全体をバックアップしてから**検証環境**で試してください。公式Paper/Velocity APIによるビルド検証は未実施です。

## 導入

- Paper/Purpur (26.2 など): `PluginBatchManager-Paper-0.1.0.jar` を対象サーバーの `plugins/` に配置
- Velocity: `PluginBatchManager-Velocity-0.1.0.jar` をプロキシの `plugins/` に配置
- 各サーバー再起動後、生成される `plugins/PluginBatchManager/plugins.yml` を編集（**環境別に別リスト**）
- `/pbm reload` または `/pbmv reload` で設定を再読み込み
- `/pbm check all`、`/pbm download all`、`/pbm apply all` の順に実行（Velocity は `pbmv`）
- 更新は**次回サーバー/プロキシ再起動時**に反映。`/reload` や PlugMan によるホットリロードではありません。

Velocity側を `pbmv` とした理由: Velocity で `/pbm` を使うとバックエンドの Paper コマンドが隠れてしまうためです。

## コマンド

| Paper/Purpur | Velocity | 機能 |
|---|---|---|
| `/pbm list` | `/pbmv list` | 登録済み一覧・配置状態 |
| `/pbm reload` | `/pbmv reload` | リスト再読み込み |
| `/pbm check all` | `/pbmv check all` | 配布元の最新版候補取得（実際の互換性は確認が必要） |
| `/pbm download all` | `/pbmv download all` | JARを一時ダウンロードしてID検証 |
| `/pbm apply all` | `/pbmv apply all` | 旧JARを`.disabled`へ退避、新JARへ置換 |
| `/pbm rollback TAB` | `/pbmv rollback TAB` | 指定プラグインの最後の退避版を復元 |

`all` の代わりに `plugins.yml` のラベルを指定可能（例 `/pbm download TAB`）。

許可: `pbm.admin`（PaperはOPに既定付与。VelocityはLuckPerms等で管理者だけに明示付与）。コンソール実行推奨。

## plugins.yml の例

このツールの設定形式は「2スペースで子要素を書く簡易YAML」です。複雑なYAML機能やインラインコメントは使用しないでください。

**Paper用** (`plugins/PluginBatchManager/plugins.yml`):

```yaml
plugins:
  ViaVersion:
    plugin-id: ViaVersion
    source: modrinth
    project: viaversion
    loader: paper
    game-version: 1.21.11
  ExampleFromGitHub:
    plugin-id: ExamplePlugin
    source: github
    repo: example-owner/example-repo
    filename-regex: '.*paper.*\.jar'
```

**Velocity用** (`plugins/PluginBatchManager/plugins.yml`):

```yaml
plugins:
  ExampleProxyPlugin:
    plugin-id: exampleproxyplugin
    source: github
    repo: example-owner/example-repo
    filename-regex: '.*velocity.*\.jar'
```

※ `example-owner` はダミーです。`plugin-id` は**実際のJAR内**の `plugin.yml`/`paper-plugin.yml` の `name` または `velocity-plugin.json` の `id` に合わせてください。ファイル名ではありません。

ソースの指定例:

```yaml
plugins:
  MyGitHub:
    plugin-id: MyPlugin
    source: github
    repo: owner/repo
    filename-regex: '.*\.jar'
  MyModrinth:
    plugin-id: MyModrinthPlugin
    source: modrinth
    project: project-slug
    loader: paper
    game-version: 1.21.11
    channel: release
  MyHangar:
    plugin-id: SomePlugin
    source: hangar
    project: ProjectSlug
    game-version: 1.21.11
  MyDirect:
    plugin-id: MyPluginDirect
    source: url
    url: https://example.net/MyPlugin-1.5.jar
```

* `filename-regex` は**JARファイル名全体**に対する正規表現で、GitHubに複数JARがある場合は必須に近いです。
* ファイル名はGitHub/Hangar/Modrinthから提供されたものを使用します。直接URLでは `Content-Disposition`、最終URL、または任意指定の `filename` から取得します。元の名前が分からない場合は**失敗**します。勝手に名前を作りません。
* HTTPは不可、HTTPSのみ。GitHub APIがレート制限する場合は環境変数 `PBM_GITHUB_TOKEN` を利用可能です。
* Modrinthはデフォルトで `release` のみを検索します。Hangarは対象プラットフォームのJARを選びます。
* `game-version` を省略した場合は**互換性を保証できません**。必要なMinecraft版に合わせて設定してください。

## ファイル処理

```text
plugins/
  ViaVersion-5.5.0.jar                  # 新JAR、元のファイル名のまま
  .disabled/
    20261025-130700-123-abcd1234/
      ViaVersion-5.4.0.jar              # 元のファイル名のまま退避
  PluginBatchManager/
    plugins.yml
    staged/
      ViaVersion/
        ViaVersion-5.5.0.jar             # download all 後、apply前
```

- `apply` はJARの内部プラグインIDを見て既存の旧JARを探します。旧JARを**削除しません**。
- 新旧同名でも `.disabled/<日時-ID>/` に分離して退避します。
- 既存の別プラグインのファイル名と競合した場合は上書きせず**失敗**します。
- ダウンロードしたプラグイン種別（Paper/Velocity）を検証し、違う種別や違うIDのものは拒否します。
- ModrinthではSHA-512、HangarではSHA-256がAPIに存在するときに検証。他ソースはダウンロード先HTTPSとJAR内部IDの確認を実施します。
- 処理は順次実行。各プラグインのファイル移動でエラーが起きた場合は、移動済み旧JARを元の場所へ戻す処理を試行します。

## 既知の制限

- **試作版**: 正式なPaper/Velocity APIライブラリを取得できない環境でビルドしました。正式ライブラリとのバイナリ動作・実際の起動・ネットワーク配布元へのアクセスは未テスト。
- `check` はオンライン最新候補を表示しますが、完全なバージョン・互換性比較は行いません。更新すべきか管理者が判断してください。
- 最新公開版がサーバーのMinecraft版で動かない場合があります。メタデータでゲーム版を指定できるModrinth/Hangarでは `game-version` を記載してください。
- GitHub `releases/latest` 方式のみ対応。GitHub ActionsやJenkins、SpigotMCなどは未実装。
- サーバーの**強制停止やクラッシュ**の瞬間に `apply` していると複数ファイルの途中状態が残る可能性があります。更新操作中は再起動しないでください。
- 検証できたのはテストJARに対するファイル操作のみ。実環境のWindows上で稼働中のJARを移動できないケースでは更新を中止する可能性があります。
- `.disabled` に蓄積した古いバックアップの自動整理、依存関係の整合性チェック、プラグイン設定ファイルのDBマイグレーションは含まれません。

## 公式依存ライブラリでの再ビルド

JDK 25 と Gradle 8.x が使え、PaperMCのMavenリポジトリにアクセスできる環境で、リポジトリルートから:

```sh
gradle :paper:jar :velocity:jar
```

成果物は `paper/build/libs/` と `velocity/build/libs/` になります。GitHub Actionsを同梱しています（GradleはActions内でセットアップします）。これが**公式APIでコンパイルできるか**は未検証です。

別途 `build-offline.sh` で試作版JARを生成できますが、`build-stubs/` はコンパイル用の仮API定義です。**実JARの中には含まれません**。外部に配布する正式版は公式APIライブラリでビルドしてください。


## GitHub ActionsからのJARダウンロード

GitHub → **Actions** → **Build PluginBatchManager** → 成功した実行 → **Artifacts** から **PluginBatchManager-Paper** または **PluginBatchManager-Velocity** をダウンロードしてください。ZIPを解凍するとJARが入っています。
