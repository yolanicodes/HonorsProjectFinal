package com.example.fitnessandmensturalcylcevisualisation

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        //Make the tool bar the top bar of the application.
        val topBar:Toolbar=findViewById(R.id.toolbar)
        setSupportActionBar(topBar)

        /*Handle button clicking. This should make sure the user enables bluetooth so that they can find their smartwatch.
        once found, they should connect.If connection is successful, the data stream via the wearable api starts. If it
        does not connect, determine the reason why it does not want to connect and possible suggestions*/

        val button: Button=findViewById(R.id.button)
        button.setOnClickListener{
            //ensure bluetooth is enables
        }

    }
}