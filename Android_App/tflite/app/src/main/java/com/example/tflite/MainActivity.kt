package com.example.tflite

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var buttonA:Button
    private lateinit var buttonB:Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Example of a call to a native method
        buttonA =findViewById(R.id.btnObjectDetection) // 전면
        buttonB=findViewById(R.id.btnObjectDetection2)  // 후면

        buttonA.setOnClickListener{
            val intent = Intent(this, ObjectDetection::class.java)
            intent.putExtra("value", 0)
            startActivity(intent)
        }
        buttonB.setOnClickListener{
            val intent = Intent(this, ObjectDetection::class.java)
            intent.putExtra("value", 1)
            startActivity(intent)
        }
    }



    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }
}