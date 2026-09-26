# タスクロック（Android）MVP 設計書

「タスク達成 → 検証 → ロック解除」を実機で成立させることを最優先にした Phase 1 の設計。
確認日: 2026-09-26（課金・ポリシーは変わりやすいため、公開前に再確認すること）

---

## 0. 事前確認の結果

| 項目 | 結果 | 対応 |
|---|---|---|
| 既存プロジェクト | このリポジトリ（fishing-marine-gis）は Python/Flask の GIS アプリで、Android 構成は無し | 既存コードに触れず `tasklock-android/` に独立した Gradle プロジェクトとして新規作成。**本来は専用リポジトリへの移動を推奨** |
| Android SDK / Google Maven | 作業環境から `dl.google.com` / `maven.google.com` に到達不可 | `:app` はこの環境ではビルド未検証。Android非依存の `:core` を切り出し、JVM で単体テスト済み（17件 pass） |
| バージョン | AGP 等は Google Maven で最新確認ができなかった | `gradle/libs.versions.toml` は実在が確実なバージョンで固定。Android Studio の更新提案で揃えること |

## 1. 仕様内の矛盾・未確定事項と判断

| # | 仕様上の論点 | 判断 |
|---|---|---|
| 1 | §5・§9 は「自由入力」を初期テンプレートに含むが、§7 は MVP を4つに限定 | データ（`TaskTemplates.ALL`）には5つ定義し、MVP の UI には4つだけ表示（`shownInMvp=false`） |
| 2 | §9「UsageStatsManager によるアプリブロック」と §8「AccessibilityService でブロック」 | **ブロックは AccessibilityService、UsageStatsManager は学習時間の検証に使用**。UsageStats によるブロックは下記 §4.3 の理由で Phase 1 では実装しない |
| 3 | 運動テンプレートの Health Connect 連携を Phase 1 に含めるか | **含めない**。Phase 1 の運動は回数の自己申告。Health Connect は権限説明画面・プライバシーポリシー・Play の健康データ申告が追加で必要で、コアフロー検証の優先順位（§8）に反するため Phase 2 |
| 4 | §9 は収益化の実装方針を求めるが、§8 はコアフロー前に課金を作り込まないと規定 | **Phase 1 は方針設計のみ**（§8 本書）。Billing ライブラリは未導入 |
| 5 | 「端末内完結」と Android 自動バックアップ | **バックアップ・端末間転送ともに無効化**。再インストールで履歴は消える（課金は Play から復元できるので影響なし） |
| 6 | ロック中に設定を緩めれば即回避できる | ロック中は「ロック対象から外す」「タスク削除」を拒否。**ロック中に簡単なタスクを新規追加する回避は未対策**（Phase 1.5 で「新規タスクは翌日から有効」等を検討） |

## 2. 技術スタック

| 領域 | 採用 | 理由 |
|---|---|---|
| 言語 | Kotlin | 仕様どおり |
| UI | Jetpack Compose + Material 3 | 画面数が少なく、ナビゲーションライブラリも不要（sealed interface で画面切替） |
| 永続化 | Room（SQLite） | 端末内完結、型付きクエリ、Flow による画面自動更新 |
| DI | なし（`AppContainer` で手組み） | MVP の規模では不要。外部SDKを増やさない方針 |
| ブロック | AccessibilityService | 前面アプリ切替をイベントで即時に受け取れる |
| 検証 | UsageStatsManager（queryEvents） | 学習アプリの前面表示時間の計測 |
| 外部SDK | **なし**（広告・分析・クラッシュ収集も入れない） | §4 のプライバシー方針 |
| minSdk / targetSdk | 26 / 36 | minSdk 26 は将来の Health Connect SDK 要件（API 26+）に合わせた |

## 3. アーキテクチャ

```
tasklock-android/
├── core/   … Android 非依存（純 Kotlin/JVM）。ここがアプリの「ルール」
│   ├── model/     Task, Completion, Verification, LockRule, UnlockGrant, VerificationStatus
│   ├── lock/      LockEvaluator（解除判定）, BlockSnapshot（ブロック判定）
│   ├── verify/    Verifier（検証結果の生成）, UsageTimeCalculator（前面時間の計算）
│   ├── template/  TaskTemplates
│   └── time/      DayBoundary（「1日」の区切り）
└── app/    … Android 依存
    ├── data/      Room エンティティ・DAO、Repository（完了→検証→解除をトランザクションで実行）
    ├── platform/  UsageStatsReader, InstalledApps, AccessibilityStatus
    ├── service/   AppLockAccessibilityService
    └── ui/        MainActivity（ホーム/タスク設定/完了/ロック対象/開示）, BlockActivity
```

判定ロジックを `:core` に置くことで、実機なしでもロック条件・検証レベル・日付境界・利用時間計算をテストできる。

## 4. データモデル（Task → Completion → Verification → Unlock）

```
Task 1 ── * Completion 1 ── * Verification
                │
                └── UnlockGrant（どの Completion で、いつまで解除したか）
LockRule（type + params）── UnlockGrant
LockedApp（ロック対象パッケージ）
```

| テーブル | 主なカラム | ポイント |
|---|---|---|
| `tasks` | category, unit, targetValue, verificationPolicy, **requiredStatus**, targetPackage, active | 解除に必要な最低検証レベルをタスクごとに持つ。削除は論理削除 |
| `completions` | taskId, **day**, reportedValue, startPage, endPage, note, completedAt | 「完了した」という申告のみ。信頼の根拠は持たない |
| `verifications` | completionId, **status**, **method**, **dataJson**, **modelVersion**, **verifiedAt** | 1つの Completion に複数の検証を付けられる。解除判定は最も強い検証で行う |
| `lock_rules` | type, paramsJson, active | MVP は `DAILY_ANY_ONE_TASK` の1件。新ルールは type 追加で拡張 |
| `unlock_grants` | day, ruleId, completionId, grantedAt, expiresAt | ブロック判定は `expiresAt` で行うため日付をまたいでも正しく再ロックされる |
| `locked_apps` | packageName, label | |

### 検証レベル
`UNVERIFIED(0) < SELF_REPORTED(1) < PARTIAL(2) < PHOTO_VERIFIED(3) < VERIFIED(4)`
解除条件は `bestStatus.rank >= task.requiredStatus.rank`。

- 自己申告タスク（学習・読書・運動）: `requiredStatus = SELF_REPORTED`
- 学習アプリ利用時間タスク（資格）: `requiredStatus = VERIFIED`（目標未達は `PARTIAL` で記録され解除されない。再確認すると同じ Completion に新しい Verification が追加される）
- 読書: ページ整合性チェックを通った場合のみ保存（method = `reading_consistency`）。客観データではないため `SELF_REPORTED`

### PHOTO_VERIFIED を後から足せるか → **スキーマ変更なしで可能**
- status / method は **文字列で保存**しているため、enum に値を足すだけでよい（既に `PHOTO_VERIFIED` / `camera_ai` を定義済み）
- `ai_result` / `confidence` は `dataJson` に、モデルは `modelVersion` に、時刻は `verifiedAt` に入る
- 画像は保存しない前提なので画像パス用カラムは不要
- 旧バージョンのアプリが未知の status を読んだ場合は `UNVERIFIED` 扱い（安全側）
- UI は「記録済み」「自動で確認済み」の2種類のみ表示（DB は2値に固定しない）

## 5. ブロック設計

### 5.1 AccessibilityService（採用）
1. `typeWindowStateChanged` のみ購読、`canRetrieveWindowContent=false`（画面内容は読まない）
2. イベントのパッケージ名を、メモリ上の `BlockSnapshot`（ロック対象集合 + 最新の UnlockGrant）で判定。イベントごとの DB アクセスはしない
3. ブロック対象なら `BlockActivity` を新しいタスクとして前面に出す。「戻る」はホーム画面へ置き換え
4. 同一アプリの連続イベントは 700ms デバウンス
5. ロック対象の候補から、自分自身・設定・ホームアプリ・既定の電話アプリを除外（緊急連絡・端末操作を妨げない）

### 5.2 回避経路（Android の仕様上防げないもの）
| 経路 | 対策 |
|---|---|
| 設定でユーザー補助をオフ／アプリの強制停止 | 防げない。ホームに「ブロック機能がオフです」を表示 |
| アプリのアンインストール・データ消去 | 防げない（デバイス管理者権限は使わない方針） |
| 端末の日時を変更 | 未対策。Phase 2 で `elapsedRealtime` との突き合わせを検討 |
| ブラウザ版 SNS・通知からの返信・ウィジェット | 未対策（ブラウザごとブロックすると学習にも支障が出るため） |
| セーフモード起動 | 防げない |
| Advanced Protection Mode（Android 16+/17） | `isAccessibilityTool=false` のサービスは権限付与が拒否・取り消しされる。**このモードの利用者には提供不可** |
| ロック中にロック対象を外す／タスク削除 | **対策済み**（Repository で拒否） |
| ロック中に簡単なタスクを追加 | 未対策（§1 #6） |

マーケティングでは「絶対に解除できない」とは言わず「意志力に頼らない仕組み」と表現する。

### 5.3 UsageStatsManager によるブロック（Phase 1 では不採用）
UsageStats でブロックする場合は、常駐フォアグラウンドサービスで `queryEvents` を1〜2秒間隔でポーリングし、前面アプリを推定する方式になる。
- 検知まで数秒の遅れがあり、SNS を一瞬開けてしまう
- Android 14+ はフォアグラウンドサービスの type 宣言が必要で、該当 type が無く `specialUse` となり Play での説明が必要
- 常時ポーリングによる電池消費
よって「AccessibilityService が使えない端末向けの代替手段」として Phase 2 以降で検討する。

### 5.4 UsageStatsManager（検証用途）の注意点
- 権限は `PACKAGE_USAGE_STATS`（ユーザーが「使用状況へのアクセス」で許可）。付与判定は AppOps で行う
- `queryUsageStats` の日次集計はバケット境界の都合で「今日0時から」を正確に切り出せないため、`queryEvents` の生イベントから計算
- イベント種別: API 29 で `MOVE_TO_FOREGROUND/BACKGROUND` → `ACTIVITY_RESUMED/PAUSED` に改名（値は同じ 1/2）。画面OFF(16)・シャットダウン(26)で区間を打ち切る
- 同一アプリ内の画面遷移で RESUMED/PAUSED の順序が前後するため、前面 Activity 数を数えて 0↔1 の遷移だけを区間とする
- イベント保持期間は OS 依存で数日。当日分の検証にだけ使う
- **「前面に表示していた時間」であって「勉強していた時間」ではない**ことを UI に明記済み

## 6. 永続化・バックアップ

- Room DB `tasklock.db` のみ。サーバー送信なし、アカウントなし、ユーザーIDの発行なし
- `allowBackup=false` + `dataExtractionRules`（Android 12+ のクラウド・端末間転送の両方）+ `fullBackupContent`（11以下）で全ドメインを除外
  - 理由: 一部メーカー端末では `allowBackup=false` だけでは端末間転送が止まらないため、ルールでも明示的に除外
- **帰結**: アンインストール・再インストール・機種変更でタスク履歴とロック設定は消える。ユーザー向けにストア説明・ヘルプで明記すること
- 課金状態は DB に依存させず、起動時に Google Play から毎回取得する（§8）ため、再インストールしても有料機能は復元される

## 7. 権限と Play 公開時の開示事項

| 権限 / API | 用途 | 必要な対応 |
|---|---|---|
| AccessibilityService | ロック対象アプリの検知 | ①アプリ内の目立つ開示と明示的同意（`AccessibilityDisclosureScreen` 実装済み）②Play Console の Accessibility API 申告フォーム（動作説明動画を求められる）③`isAccessibilityTool=false`（障害者支援ツールではないため）。2026年1月28日以降は審査が厳格化 |
| `PACKAGE_USAGE_STATS` | 学習アプリ利用時間の検証 | 特殊アクセス権限。ユーザーが設定から許可。公開前に最新の Play ポリシーで申告要否を再確認 |
| アプリ一覧取得 | ロック対象の選択 | `QUERY_ALL_PACKAGES`（制限付き）は**使わず** `<queries>` でランチャー表示アプリに限定 |
| Health Connect（Phase 2） | 歩数検証 | `READ_STEPS` 宣言、権限説明 Activity（Android 13以下: `ACTION_SHOW_PERMISSIONS_RATIONALE`、14+: `VIEW_PERMISSION_USAGE` の activity-alias）、Play Console での健康データ申告 |

データ安全性セクション: 端末内でのみ処理しサーバー送信しないため「収集なし・共有なし」で申告できる見込み。ただし公開前に、実装に基づき Play の定義で再確認すること。プライバシーポリシー URL は Accessibility 利用アプリとして必須。

Android 13+ の「制限付き設定」: ストア以外から導入した APK ではユーザー補助の許可が灰色になることがある。その場合はアプリ情報の右上メニューから「制限付き設定を許可」。

## 8. 収益化方針（Phase 1 では実装しない）

### 前提（公式ドキュメントで確認）
- 最新は Play Billing Library **9.1.0**（2026-06-18）
- **2026年8月31日以降、新規アプリ・更新は PBL 8 以上が必須**（延長申請で 2026年11月1日まで）
- PBL 8 で `queryPurchaseHistoryAsync` は削除。購入の復元は `queryPurchasesAsync(QueryPurchasesParams(ProductType.SUBS))` を使う
- `enablePendingPurchases(PendingPurchasesParams)` が必須、`enableAutoServiceReconnection()` で再接続処理が不要
- 購入の取得に必要なのは**端末の Google アカウントのみ**。アプリ独自のアカウントは不要
- 新規購入は **3日以内に acknowledge** しないと自動返金される（更新分は不要）

### 設計
```
BillingClient.newBuilder(context)
    .setListener(purchasesUpdatedListener)
    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
    .enableAutoServiceReconnection()
    .build()
```
1. 起動時と `onResume` で `queryPurchasesAsync(SUBS)` → `PURCHASED` かつ有効なものがあれば Premium。**これが「購入の復元」を兼ねる**（復元ボタンも同じ処理を呼ぶだけ）
2. 未 acknowledge の購入は acknowledge する。`PENDING` は権利付与しない
3. 権利状態は DB に保存せず、メモリ上の `Entitlement` として保持（前回値のキャッシュのみ SharedPreferences、バックアップ対象外）
4. `obfuscatedAccountId` は任意項目なので設定しない（ユーザーIDを発行しない方針のため）
5. サーバーを持たないため、購入トークンのサーバー検証・RTDN は行わない。改ざんAPKによる不正利用のリスクは受け入れる（低価格サブスクでは費用対効果が合わない）。規模が大きくなったら最小のサーバーレス検証を再検討

### 無料/有料の線引き案
- 無料: タスク1つ、ロック対象アプリ3つまで、コアフローはすべて使える
- Premium: タスク・ロック対象の無制限、写真検証（Phase 3）、Health Connect 連携、日付境界の変更など
- 目安: 月額300円・Google 手数料15%（定期購入）で手取り約255円 → 月10万円に約400人の有料会員

## 9. Phase 1 の範囲

### 実装済み
- タスク設定（テンプレート4種 → 目標値 → 学習アプリ選択）
- タスク完了（数量申告 / 読書のページ整合性チェック / 学習アプリの利用時間計測）
- Completion と Verification の分離保存、解除判定、UnlockGrant 記録
- ロック対象アプリ選択（ロック中は外せない）
- AccessibilityService によるブロック + ブロック画面
- Accessibility の目立つ開示と同意画面
- バックアップ無効化

### 実装しない（Phase 2 以降）
保護者機能 / アカウント / クラウド同期 / ソーシャル / 画像アップロード / AI写真判定 / 統計・ランキング / 課金 / Health Connect / 自由入力タスクの UI / 複数タスク組み合わせ・時間帯・曜日ルール / 外部SDK・広告

## 10. 実機テスト手順

### ビルド
1. Android Studio で `tasklock-android/` を開く（リポジトリのルートではない）
2. Gradle Sync。バージョン更新の提案が出たら適用
3. 単体テスト: `./gradlew :core:test`
4. USB デバッグを有効にした実機を接続し、`app` を Run（または `./gradlew :app:installDebug`）

### A. 初回セットアップ
1. アプリを開く → 「ブロック機能がオフです」が表示される
2. 「オンにする」→ 開示画面 → 「同意して設定を開く」→ ユーザー補助で「タスクロック」をオン
   - 灰色で押せない場合: 設定 > アプリ > タスクロック > 右上メニュー > 「制限付き設定を許可」
3. アプリに戻る → 表示が「ロックするアプリが未設定です」に変わる
4. 「アプリを選ぶ」→ SNS アプリ（例: X、YouTube）にチェック → 「完了」
5. 「タスクを追加」→「本を読む」→ 目標 5 ページ → 保存

### B. ロック（コアフロー前半）
1. ホームが「🔒 ロック中」になっている
2. ホーム画面から SNS アプリを開く → 1秒以内にブロック画面が出る
3. 「戻る」→ SNS ではなくホーム画面に戻る
4. 最近使ったアプリ一覧から SNS を選ぶ → 再びブロック画面
5. タスクロックの「ロック対象」で SNS のチェックを外す → 「ロック中は対象から外せません」
6. ホームでタスクの「削除」→ 拒否される

### C. 解除（コアフロー後半）
1. ブロック画面の「今日のタスクへ」→ ホーム →「完了を記録する」
2. 開始 1 / 終了 3 → 「完了した」が押せない（3ページ < 目標5）
3. 終了 5 → 「完了した」→「🎉 おつかれさまでした」
4. ホームが「🔓 今日は解除済み」→ SNS が普通に開ける
5. 翌日（または端末時刻を翌日0時過ぎに変更）→ SNS を開くと再びブロックされる

### D. 読書の整合性
1. 翌日、前回終了ページが表示され、開始ページが前回+1 で入っている
2. 開始ページを大きく飛ばす（例: 前回5 → 開始50）→ エラー表示、完了不可

### E. 学習アプリ利用時間（VERIFIED）
1. 「資格の学習アプリで勉強」→ 目標 2 分 → 「許可する」で使用状況へのアクセスを許可 → 学習アプリ（例: 電卓でも可）を選んで保存
2. すぐに「利用時間を確認する」→ 0〜1分表示 →「確認して完了」→「あと◯分です」（解除されない＝PARTIAL/UNVERIFIED）
3. 学習アプリを3分開いてから戻る → 再確認 → 解除される（VERIFIED）

### F. 回避経路の確認（仕様上防げないことの確認）
1. ユーザー補助でタスクロックをオフ → SNS が開ける、ホームに「ブロック機能がオフです」
2. アンインストール → 再インストール → タスク・ロック設定が消えている（バックアップ無効の確認）

### 確認できると良いメーカー差
Pixel / Galaxy / Xiaomi 系で、B-2 の表示遅延とバックグラウンドでのサービス停止（省電力設定）を確認する。
