package com.meuapp.iptvplayer.util

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meuapp.iptvplayer.data.model.Category
import java.text.Normalizer

/** Detecta categorias de conteúdo adulto (Live TV e Filmes) e protege elas
 * com o PIN do controle parental (Ajustes) -- usado tanto em VodActivity
 * quanto em ChannelListActivity, pra não duplicar a mesma lógica. */
object AdultContentGuard {

    private val keywords = listOf("adulto", "adult", "+18", "18+", "xxx", "erotic", "erotico", "sexo", "hot", "porn")

    fun isAdultCategory(name: String): Boolean {
        val normalized = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return keywords.any { normalized.contains(it) }
    }

    /** Categorias normais primeiro (ordem alfabética), categorias adultas
     * escondidas no final (também em ordem alfabética entre si). */
    fun sortWithAdultLast(categories: List<Category>): List<Category> {
        val (adult, normal) = categories.partition { isAdultCategory(it.categoryName) }
        return normal.sortedBy { it.categoryName.lowercase() } + adult.sortedBy { it.categoryName.lowercase() }
    }

    /** Se a categoria é adulta E o usuário tem um PIN configurado, pede o
     * PIN antes de continuar. Categoria normal, ou adulta sem PIN
     * configurado, passa direto. */
    fun guardCategorySelection(activity: Activity, category: Category, onAllowed: () -> Unit) {
        if (!isAdultCategory(category.categoryName)) {
            onAllowed()
            return
        }
        val savedPin = activity.getSharedPreferences("supremus_settings", Context.MODE_PRIVATE)
            .getString("parental_pin", null)
        if (savedPin.isNullOrBlank()) {
            onAllowed()
            return
        }
        val input = EditText(activity).apply {
            hint = "PIN com 4 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        AlertDialog.Builder(activity)
            .setTitle("Conteúdo adulto")
            .setMessage("Digite o PIN do controle parental para continuar.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                val typed = input.text.toString().filter { it.isDigit() }
                if (typed == savedPin) {
                    onAllowed()
                } else {
                    Toast.makeText(activity, "PIN incorreto", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
