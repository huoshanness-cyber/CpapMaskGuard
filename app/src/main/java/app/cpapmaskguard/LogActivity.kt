package app.cpapmaskguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.cpapmaskguard.databinding.ActivityLogBinding
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.util.AppLogger
import app.cpapmaskguard.util.LogMailer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)
        binding.refreshButton.setOnClickListener { refresh() }
        binding.copyButton.setOnClickListener { copyAll() }
        binding.shareButton.setOnClickListener { shareAll() }
        binding.mailButton.setOnClickListener { sendMail() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.backButton.setOnClickListener { finish() }
        refresh()
    }

    private fun refresh() {
        binding.recipientsLabel.text = getString(R.string.log_recipients, settings.reportEmailsCsv())
        binding.logText.text = AppLogger.readTail()
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun copyAll() {
        val text = binding.logText.text?.toString().orEmpty()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("CpapMaskGuard log", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareAll() {
        val text = binding.logText.text?.toString().orEmpty()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "面罩守护日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享日志"))
    }

    private fun sendMail() {
        val ok = LogMailer.sendLogEmail(this, "手动发送", force = true)
        if (ok) {
            Toast.makeText(this, "请在邮件应用里确认发送", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空日志？")
            .setMessage("会删掉本机保存的应用日志文件。")
            .setPositiveButton("清空") { _, _ ->
                AppLogger.clear()
                refresh()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
