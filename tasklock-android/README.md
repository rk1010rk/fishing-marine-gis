# タスクロック（Android MVP）

タスク（学習・資格・読書・運動）を終えるまで、指定した SNS・ゲームアプリを開けなくする「タスク達成型アプリロッカー」。
アカウント不要・端末内完結・外部SDKなし。

- 設計・制約・権限・課金方針・実機テスト手順: [docs/DESIGN.md](docs/DESIGN.md)

## 構成

| モジュール | 内容 |
|---|---|
| `core` | Android 非依存のドメイン層（データモデル、ロック判定、検証ロジック、テンプレート）。JVM で単体テスト可能 |
| `app` | Android アプリ（Room、AccessibilityService、UsageStatsManager、Compose UI） |

## ビルド

Android Studio でこのディレクトリ（`tasklock-android/`）を開く。

```sh
./gradlew :core:test        # ドメイン層の単体テスト
./gradlew :app:installDebug # 実機にインストール
```
