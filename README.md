# はんていくん（HANTEIKUN / 圧着工具 Eリング有無判定アプリ）

## 構成

```
app/src/main/java/com/example/eringchecker/
├── ui/
│   ├── MainActivity.kt        ホーム画面（縦固定）: サイズ選択・ボタン有効/無効制御・遷移
│   └── CameraActivity.kt      カメラ画面（横固定）: サンプル撮影モード / 判定モード
├── camera/
│   ├── Roi.kt                 中央正方形 ROI の定義（現在は未使用）
│   ├── GuideOverlayView.kt    ガイド枠の描画（現在は非表示）
│   └── RoiFrameAnalyzer.kt    CameraX フレーム → プレビュー全体のビットマップ切り出し
├── image/
│   ├── ImageComparator.kt     ビットマップ直接処理による特徴抽出・類似度計算
│   ├── ToolDetector.kt        背景画像との差分（最大変化領域の面積）による工具検知
│   ├── RingRegion.kt          リングあり／なし基準画像の差分からリング領域を抽出し、領域内で比較
│   └── RingJudge.kt           工具検知後に 2 枚の基準画像と比較・判定、静止待ち→1 回判定→枠外まで保持（JudgeSession）
└── data/
    ├── ToolSize.kt            登録サイズ一覧（AK-17A／P-738）
    └── SampleStore.kt         基準画像の保存（files/samples/<sizeId>/{no-ring,has-ring,background}.png）
```

- 言語: Kotlin（AGP 9 内蔵 Kotlin）
- カメラ: CameraX 1.6（Preview + ImageAnalysis、ViewPort で表示範囲と解析範囲を一致）
- 画像処理: OpenCV なし。グレースケール＋Sobel エッジの正規化相互相関（±4px の位置ずれ許容）
- ビルド: AGP 9.4.1 / Gradle 9.8.0 / compileSdk 37 / targetSdk 36 / minSdk 24

## 画面フロー

1. ホーム画面でサイズを選択 → 「サンプル撮影」「判定開始」が有効化（未選択時はグレーアウト）
2. 「判定開始」押下時、そのサイズの基準画像（リングなし・リングあり・背景のみ）がそろっていなければ
   Toast で警告し、サンプル撮影画面へ自動遷移
3. サンプル撮影画面で「リングなしを撮影」「リングありを撮影」、工具をどけて「背景のみを撮影」
4. 3 枚そろうと「判定開始」が有効になり、同じ画面のまま判定モードへ切り替え
5. 判定モードでは 250ms ごとに、まず背景画像との差分で工具の有無を検知し（検知中は左上に「！」）、
   工具があればカメラの揺れが収まるのを待ってから 1 回だけ 2 枚の基準画像と比較し、OK なら緑の〇、NG なら赤の×を表示
   - 判定結果は工具をガイド枠の外に出すまで保持し、再判定しない
   - 工具が枠外に出る（非検知が続く）と「！」「〇/×」をリセットし、次の検知に備える
   - 比較は 2 枚の基準画像が食い違う「リング領域」だけで行う
   - リング領域が「リングあり」に十分似ていて、かつ「リングなし」より明確に似ているときだけ〇。
     それ以外（テープで隠れている等）はすべて×（見逃し防止優先）

## 判定ロジックの調整

`image/RingJudge.kt`・`image/RingRegion.kt`・`image/ToolDetector.kt` の定数で調整します。

| 定数 | 既定値 | 意味 |
|---|---|---|
| `DIFF_THRESHOLD` | 30 | 背景との輝度差（0〜255）がこれを超える画素を「変化あり」とする |
| `MIN_AREA_FRACTION` | 0.02 | 工具ありとみなす最大変化領域の下限（画面面積比） |
| `REF_AREA_RATIO` | 0.4 | 基準画像で測った工具の面積のこの割合以上が見えていれば工具あり |
| `MIN_SIMILARITY` | 0.35 | 画面全体がどちらの基準にもこれ未満なら「対象なし」 |
| `BACKGROUND_MARGIN` | 0.05 | 画面全体が「背景のみ」より基準画像にこれ以上似ていなければ「対象なし」（工具をどけた後の誤検知防止） |
| `RING_MIN_SIMILARITY` | 0.6 | リング領域で「リングあり」との類似度がこれ未満なら× |
| `RING_MARGIN` | 0.15 | リング領域で「リングあり」が「リングなし」をこれ以上上回らなければ× |
| `RingRegion.DIFF_THRESHOLD` | 25 | 2 枚の基準画像の輝度差がこれを超える画素をリング領域とする |
| `JudgeSession.MOTION_THRESHOLD` | 4 | 前フレームとの平均輝度差がこれ未満なら「静止」とみなす |
| `JudgeSession.STABLE_FRAMES` | 3 | 静止フレームがこの回数（250ms 間隔）続いたら 1 回だけ判定 |
| `JudgeSession.SETTLE_TIMEOUT_MS` | 3000 | 揺れが収まらなくても、検知からこの時間で判定 |
| `JudgeSession.WINDOW_SIZE` | 5 | 〇×表示は直近この回数の判定の多数決で決める（チラつき防止） |
| `JudgeSession.ABSENT_FRAMES` | 2 | 非検知がこの回数続いたら表示をリセット |

変化領域・リング領域の類似度・工具検知のしきい値は Logcat（タグ `CameraActivity`）に出力されるので、それを見ながら実機で調整してください。判定領域はプレビュー全体（前面カメラ、プレビューと同じく左右反転）。
基準画像と判定時で撮影距離・照明・工具の向きをそろえるほど精度が上がります。

## 注意

- targetSdk 36（Android 16）は大画面端末（短辺 600dp 以上）で画面向き固定を無視するため、
  マニフェストで `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` によるオプトアウトを設定済みです。
  targetSdk を 37 以上に上げるとこのオプトアウトは使えなくなります。
- `local.properties` はこの PC の SDK パスです（Git 管理対象外）。
