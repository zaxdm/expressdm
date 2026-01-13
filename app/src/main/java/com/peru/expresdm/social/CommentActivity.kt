package com.peru.expresdm.social

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.peru.expresdm.R

class CommentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comment)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.comment_title)

        val input = findViewById<TextInputEditText>(R.id.inputComment)
        val btn = findViewById<Button>(R.id.btnPublish)
        btn.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                Toast.makeText(this, R.string.comment_published, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, R.string.comment_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
