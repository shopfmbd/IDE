package __PKG__;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    static { System.loadLibrary("rustlib"); }

    public native String hello();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btn).setOnClickListener(v ->
                Toast.makeText(this, hello(), Toast.LENGTH_SHORT).show());
    }
}
