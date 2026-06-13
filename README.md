### HonorsProjectFinal Complete installation guide for the WearOS application
---
## What this application does
This application visualises integrated fitness and menstrual cycle data in almost real-time on a smartwatch emulator in Android Studio. The system runs a simulation using two CSV Files as the data source mimicking a data stream for physiological metrics such as heart rate and energy, and workout data using historical data adjusted for a  real-time simulation. The application displays menstrual cycle information alongside health information and fitness data during workouts. the system also allows users to view historical fitness and health data with menstrual cycle data integrated

---
## Step 1- Install Android Studio Narwhall And Setup your environme
1. Open your web browser and navigate to the following webpage and follow the instructions to install  Android studio
2. Download the ZIP file of the code from this Github repository
3. Unzip the file onto your local device
4. Generate 2 CSV Files:
   # P102-Health_Data_Processed.csv with 20000 records and the following structure:
      StartTime,ActiveEnergyBurned,BasalBodyTemperature,BasalEnergyBurned,HeartRate,RestingHeartRate,StepCount,VO2Max,MenstrualCyclePhase,MenstrualCycle Day
2023/08/01 00:00:00,43,25,36.4,110,120,430,102.4,Follicular,20

    # P102-Workout_Data.csv with 20 records and the following structure
    workoutActivityType,duration,distance,AverageHeartRate,activeEnergyBurned,basalEnergyBurned,startDate,endDate
Walking,19.20329468,1.22171,99.6665,46.3916,23.2598,2023/07/02 09:34:30,2023/07/02 09:53:42

6. Download the WearOS Large Round Emulator in the Device Manager found under the Tools menu

## Step 2- Install the Application on the emulator
1. After launching the emulator, run the following tile services:
   MainTileService.kt
   StepCountTileService.kt
   WatchfaceTileService.kt

2. Run the MainActivity.kt file after installing the tile services.
3. For the age, set up  any age and for the stepcount, select a low value (i.e 1000)
4. Select the simulation to run in evaluation mode.
5. Start the simulation and wait 5 minutes while the simulation loads all the historical data .
6. Click the back button and locate the tile services to view the integrated data from the data stream. 
