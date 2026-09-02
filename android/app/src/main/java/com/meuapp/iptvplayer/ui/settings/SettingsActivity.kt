package com.meuapp.iptvplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setMargins
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivitySettingsBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val renciaRepository = RenciaRepository()
    private val colorOptions = listOf(
        "Verde Supreme" to AppearancePrefs.DEFAULT_COLOR,
        "Verde esmeralda" to "#3F8F63",
        "Oliva" to "#6F7F3E",
        "Dourado" to "#C6A85B",
        "Prata" to "#B9C0B4",
        "Verde profundo" to "#31543C",
        "Bronze" to "#8B7040",
        "Cinza grafite" to "#68706A"
    )
    private var selectedColorHex: String = AppearancePrefs.DEFAULT_COLOR
    private val colorSwatchViews = mutableListOf<Pair<String, ImageView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.settings_title)
        binding.toolbar.tvSubtitle.text = "Configurações do SUPREME"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        selectedColorHex = AppearancePrefs.getCategoryBarColor(this)
        setupCategoryBarSwitch()
        setupColorGrid()
        setupBackdropSwitch()
        updateColorSectionVisibility()
        setupFunctionalRows()
    }

    private fun setupFunctionalRows() {
        val session = SessionStore.getSavedSession(this)
        val mac = session?.mac.orEmpty()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")

        configureRow(R.id.rowAccount, "●", "Conta", session?.status ?: "Aparelho ativado") {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        configureRow(R.id.rowDevice, "ID", "MAC do dispositivo", mac.ifBlank { "Não informado" }) {
            if (mac.isBlank()) {
                showInfo("MAC do dispositivo", "Nenhum MAC foi cadastrado ainda.")
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MAC", mac))
                Toast.makeText(this, "MAC copiado: $mac", Toast.LENGTH_SHORT).show()
            }
        }
        configureRow(R.id.rowSupport, "?", "Suporte / Revendedor", "Fale com o responsável pela sua lista") {
            showInfo("Suporte / Revendedor", "Entre em contato com o revendedor responsável pelo seu acesso.")
        }
        configureRow(R.id.rowCache, "×", "Cache", "Limpar arquivos temporários do aplicativo") {
            cacheDir.deleteRecursively()
            externalCacheDir?.deleteRecursively()
            Toast.makeText(this, "Cache limpo", Toast.LENGTH_SHORT).show()
        }
        configureRow(R.id.rowLanguage, "文", "Idioma", "Português (Brasil)") {
            showInfo("Idioma", "Português (Brasil) está selecionado.")
        }
        configureRow(R.id.rowParental, "P", "Controle parental", "Definir ou alterar PIN") {
            showParentalPinDialog()
        }
        configureRow(R.id.rowPlayer, "▶", "Player", "Preferências de reprodução") {
            showPlayerOptions()
        }
        configureRow(R.id.rowDiagnostics, "✓", "Diagnóstico", "Testar conexão e autorização do painel") {
            runDiagnostic(mac)
        }
        configureRow(R.id.rowVersion, "i", "Versão", "SUPREME v$version") {
            showInfo("SUPREME", "Versão $version\nPlayer Media3 ativo\nRádio Browser disponível")
        }
        configureRow(R.id.rowLogout, "↪", "Sair / Trocar dispositivo", "Apagar a sessão deste aparelho") {
            AlertDialog.Builder(this)
                .setTitle("Sair do aparelho?")
                .setMessage("Você precisará informar o MAC novamente para ativar o aplicativo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Sair") { _, _ ->
                    SessionStore.clear(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finishAffinity()
                }
                .show()
        }
    }

    private fun configureRow(id: Int, icon: String, title: String, subtitle: String, action: () -> Unit) {
        val row = findViewById<LinearLayout>(id)
        row.findViewById<android.widget.TextView>(R.id.tvRowIcon).text = icon
        row.findViewById<android.widget.TextView>(R.id.tvRowTitle).text = title
        row.findViewById<android.widget.TextView>(R.id.tvRowSubtitle).text = subtitle
        row.setOnClickListener { action() }
    }

    private fun runDiagnostic(mac: String) {
        if (mac.isBlank()) {
            showInfo("Diagnóstico", "Nenhum MAC cadastrado para testar.")
            return
        }
        Toast.makeText(this, "Testando conexão...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            renciaRepository.verifyAccess(mac)
                .onSuccess { access ->
                    showInfo(
                        "Diagnóstico",
                        "Servidor: conectado\nAutorização: ${if (access.allowed) "liberada" else "bloqueada"}"
                    )
                }
                .onFailure { error -> showInfo("Diagnóstico", error.message ?: "Falha na conexão") }
        }
    }

    private fun showParentalPinDialog() {
        val input = EditText(this).apply {
            hint = "PIN com 4 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("Controle parental")
            .setMessage("Defina um PIN para proteger conteúdo adulto.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val pin = input.text.toString().filter { it.isDigit() }
                if (pin.length == 4) {
                    getSharedPreferences("supremus_settings", MODE_PRIVATE).edit().putString("parental_pin", pin).apply()
                    Toast.makeText(this, "PIN salvo", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Use exatamente 4 dígitos", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showPlayerOptions() {
        val options = arrayOf("Reprodução automática", "Repetir em caso de falha", "Controles sempre visíveis")
        val prefs = getSharedPreferences("supremus_settings", MODE_PRIVATE)
        val selected = prefs.getInt("player_option", 0)
        AlertDialog.Builder(this)
            .setTitle("Preferências do player")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                prefs.edit().putInt("player_option", which).apply()
                dialog.dismiss()
                Toast.makeText(this, options[which], Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showInfo(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupCategoryBarSwitch() {
        binding.switchCategoryBar.isChecked = AppearancePrefs.isCategoryBarEnabled(this)
        binding.switchCategoryBar.setOnCheckedChangeListener { _, isChecked ->
            AppearancePrefs.setCategoryBarEnabled(this, isChecked)
            updateColorSectionVisibility()
        }
    }

    private fun setupBackdropSwitch() {
        binding.switchBackdrop.isChecked = AppearancePrefs.isBackdropPosterEnabled(this)
        binding.switchBackdrop.setOnCheckedChangeListener { _, isChecked ->
            AppearancePrefs.setBackdropPosterEnabled(this, isChecked)
            binding.backdropView.setPoster(null, isChecked)
        }
    }

    private fun setupColorGrid() {
        binding.gridColors.removeAllViews()
        colorSwatchViews.clear()
        val density = resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val marginPx = (6 * density).toInt()
        colorOptions.forEach { (_, hex) ->
            val swatch = ImageView(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sizePx
                    height = sizePx
                    setMargins(marginPx)
                }
                background = buildSwatchDrawable(hex, selected = hex == selectedColorHex)
                setOnClickListener {
                    selectedColorHex = hex
                    AppearancePrefs.setCategoryBarColor(this@SettingsActivity, hex)
                    refreshSwatchSelection()
                }
            }
            colorSwatchViews.add(hex to swatch)
            binding.gridColors.addView(swatch)
        }
    }

    private fun refreshSwatchSelection() {
        colorSwatchViews.forEach { (hex, view) ->
            view.background = buildSwatchDrawable(hex, selected = hex == selectedColorHex)
        }
    }

    private fun buildSwatchDrawable(hex: String, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(try { Color.parseColor(hex) } catch (_: IllegalArgumentException) { Color.GRAY })
            setStroke(if (selected) (3 * resources.displayMetrics.density).toInt() else 0, Color.WHITE)
        }
    }

    private fun updateColorSectionVisibility() {
        val visible = if (AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE
        binding.dividerColors.visibility = visible
        binding.tvChooseColor.visibility = visible
        binding.gridColors.visibility = visible
    }
}
