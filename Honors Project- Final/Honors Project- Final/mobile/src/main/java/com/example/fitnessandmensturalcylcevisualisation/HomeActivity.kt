package com.example.fitnessandmensturalcylcevisualisation

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var dataClient:DataClient
    private lateinit var nodeClient:NodeClient
    private lateinit var  button:Button

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

        //Initialise Wearable clients
        dataClient=Wearable.getDataClient(this)
        nodeClient=Wearable.getNodeClient(this)

        button=findViewById(R.id.button)
        button.setOnClickListener{
            //ensure bluetooth is enables
            //find watch nearby and connect to watch.
            //once connection is established, read through the data and update visualisations in real-time as info is being read
            connectToWatch()
        }

    }

    private suspend fun getConnectedNodes(): List<Node> {

        return TODO("Provide the return value")
    }

    private fun connectToWatch() {
        TODO("Finds the watch nearby and connects to it.")

        button.isEnabled=false
        button.text="Connecting..."

        CoroutineScope(Dispatchers.Main).launch {
            try{
                val connectedNodes= getConnectedNodes()

                if(connectedNodes.isNotEmpty()){

                }
            }
            catch (e: Exception){

            }
        }
    }
}