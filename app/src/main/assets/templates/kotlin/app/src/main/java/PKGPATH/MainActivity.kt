package __PKG__

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.btn).setOnClickListener {
            Toast.makeText(this, "It works!", Toast.LENGTH_SHORT).show()
        }
    }
}
