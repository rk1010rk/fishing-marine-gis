package jp.tasklock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * AccessibilityService API 利用前の「目立つ開示と同意」（Google Play ポリシー要件）。
 * - アプリ内の通常の操作フローで表示する（設定メニューの奥に置かない）
 * - 取得するデータ・用途・外部送信の有無を明記する
 * - 明示的な同意操作（ボタン）を求め、同意しない選択肢も用意する
 */
@Composable
fun AccessibilityDisclosureScreen(onAgree: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("ユーザー補助（アクセシビリティ）機能の利用について", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Section(
            "何のために使うか",
            "あなたが選んだアプリ（SNS・ゲーム等）が開かれたことを検知し、今日のタスクが終わるまでブロック画面を表示するためだけに使います。",
        )
        Section(
            "取得する情報",
            "いま画面に表示されているアプリの種類（パッケージ名）のみです。画面に表示されている文字・画像・入力内容・パスワード等は読み取りません。",
        )
        Section(
            "情報の送信・共有",
            "取得した情報は端末の中だけで使い、保存もしません。開発者を含む第三者へ送信・共有することはありません。",
        )
        Section(
            "いつでも停止できます",
            "端末の「設定 > ユーザー補助」からいつでもオフにできます。オフにするとアプリのブロックは行われません。",
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) { Text("同意して設定を開く") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("同意しない") }
        Spacer(Modifier.height(16.dp))
        Text(
            "設定画面で「タスクロック」を選び、オンにしてください。表示されない場合は、アプリ情報画面の右上メニューから「制限付き設定を許可」を選んでから再度お試しください。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
}
