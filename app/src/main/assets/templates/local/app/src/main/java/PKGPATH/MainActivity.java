package __PKG__;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button b = findViewById(R.id.btn);
        b.setOnClickListener(v -> Toast.makeText(this, "Built on the phone. It works!", Toast.LENGTH_SHORT).show());
    }
}
