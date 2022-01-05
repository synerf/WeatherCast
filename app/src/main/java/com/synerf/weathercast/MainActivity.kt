package com.synerf.weathercast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.synerf.weathercast.databinding.ActivityMainBinding
import com.synerf.weathercast.models.WeatherResponse
import com.synerf.weathercast.network.WeatherService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // A fused location client variable which is further used to get the user's current location
    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private lateinit var mSharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)

        setupUI()

        // checking if location is ON or OFF
        if (!isLocationEnabled()) {
            Toast.makeText(
                this,
                "Your location provider is turned OFF. Please turn it ON.",
                Toast.LENGTH_SHORT
            ).show()
            // if not on then take user to location settings
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } else {
            Dexter.withActivity(this)
                .withPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            requestLocationData()
                        }

                        if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "You have denied location permission. Please allow it as it is mandatory.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permissions: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        showRationalDialogForPermissions()
                    }
                }).onSameThread()
                .check()
        }
    }

    /**
     * function to check if system location is ON
     */
    private fun isLocationEnabled(): Boolean {
        // This provides access to system location services
        val locationManager: LocationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * function to show the progress dialog (loading screen)
     */
    private fun showCustomProgressDialog() {
        mProgressDialog = Dialog(this)

        // set the screen content from a layout resource
        // the resource will be inflated, adding all top level views to the screen
        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)

        // start the dialog and display it on the screen
        mProgressDialog!!.show()
    }

    /**
     * function setting menu in toolbar (currently only refresh option is set)
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * function to handle item selection from menu (currently only refresh option is handled)
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.actionRefresh -> {
                requestLocationData()
                true
            } else -> {
                return super.onOptionsItemSelected(item)
            }
        }
    }

    /**
     * function to hide progress dialog
     */
    private fun hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
    }

    /**
     * A function to request the current location using the fused location provider client.
     */
    @SuppressLint("MissingPermission")
    private fun requestLocationData() {
        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        mFusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()!!
        )
    }

    /**
     * A location callback object of fused location provider client where we will get the current location details.
     */
    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation
            val latitude = mLastLocation.latitude
            Log.e("Current Latitude", "$latitude")
            val longitude = mLastLocation.longitude
            Log.e("Current Longitude", "$longitude")

            // call getLocationWeatherDetails function once we have latitude and longitude
            getLocationWeatherDetails(latitude, longitude)

            mFusedLocationClient.removeLocationUpdates(this)
        }
    }

    /**
     * function to get the weather details of the current location based on the latitude longitude
     */
    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if(Constants.isNetworkAvailable(this)) {

            // setting up retrofit and API call -- also added Gson converter factory
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            // Here we map the service interface in which we declare the end point and the API type
            // i.e GET, POST and so on along with the request parameter which are required.
            val service: WeatherService = retrofit.create<WeatherService>(WeatherService::class.java)

            // An invocation of a Retrofit method that sends a request to a web-server and returns a response.
            // Here we pass the required param in the service
            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID
            )

            // show progress dialog before making the api call
            showCustomProgressDialog()

            // Callback methods are executed using the Retrofit callback executor.
            listCall.enqueue(object: Callback<WeatherResponse>{
                override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                    if (response.isSuccessful) {
                        // hide progress dialog
                        hideProgressDialog()

                        val weatherList: WeatherResponse = response.body()!!

                        // preparing to save response in shared preference
                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()

                        // setup UI
                        setupUI()
                        Log.i("Response Result", "$weatherList")
                    } else {
                        val rc = response.code()
                        when(rc) {
                            400 -> {
                                Log.e("Error 400", "Bad connection")
                            }
                            404 -> {
                                Log.e("Error 404", "Not found")
                            }
                            else -> {
                                Log.e("Error", "Generic error")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("Errrrorr", t!!.message.toString())
                    // hide progress dialog
                    hideProgressDialog()
                }
            })

        } else {
            Toast.makeText(
                this@MainActivity,
                "No internet connection available",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * A function used to show the alert dialog when the permissions are denied and need to allow it from settings app info.
     */
    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this)
            .setMessage("It Looks like you have turned off permissions required for this feature. It can be enabled under Application Settings")
            .setPositiveButton("GO TO SETTINGS") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }.show()
    }

    /**
     * function to assign values to textviews
     */
    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        // getting the data stored in shared preference
        val weatherResponseJsonString = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, "")

        if (!weatherResponseJsonString.isNullOrEmpty()) {
            val weatherList = Gson().fromJson(weatherResponseJsonString, WeatherResponse::class.java)

            for(i in weatherList.weather.indices) {
                Log.i("Weather Name", weatherList.weather.toString())

                // assigning all textviews values
                binding.tvMain.text = weatherList.weather[i].main
                binding.tvMainDescription.text = weatherList.weather[i].description
                binding.tvTemp.text = weatherList.main.temp.toString() + getUnit(application.resources.configuration.locales.toString())
                binding.tvHumidity.text = weatherList.main.humidity.toString() + " per cent"
                binding.tvMin.text = weatherList.main.temp_min.toString() + " min"
                binding.tvMax.text = weatherList.main.temp_max.toString() + " max"
                binding.tvSpeed.text = weatherList.wind.speed.toString()
                binding.tvName.text = weatherList.name
                binding.tvCountry.text = weatherList.sys.country
                binding.tvSunriseTime.text = unixTime(weatherList.sys.sunrise)
                binding.tvSunsetTime.text = unixTime(weatherList.sys.sunset)

                // setting weather icon
                when(weatherList.weather[i].icon) {
                    "02d" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "03d" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "04d" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "01d" -> binding.ivMain.setImageResource(R.drawable.sunny)
                    "04n" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "10d" -> binding.ivMain.setImageResource(R.drawable.rain)
                    "11d" -> binding.ivMain.setImageResource(R.drawable.storm)
                    "13d" -> binding.ivMain.setImageResource(R.drawable.snowflake)
                    "01n" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "02n" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "03n" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "10n" -> binding.ivMain.setImageResource(R.drawable.cloud)
                    "11n" -> binding.ivMain.setImageResource(R.drawable.rain)
                    "13n" -> binding.ivMain.setImageResource(R.drawable.snowflake)
                }
            }
        }

    }

    /**
     * function to get temperature unit
     */
    private fun getUnit(value: String): String {
        var value = "°C"
        if("US" == value || "LR" == value || "MM" == value) {
            value = "°F"
        }
        return value
    }

    /**
     * function to convert unix time to human time
     */
    private fun unixTime(timex: Long): String? {
        val date = Date(timex * 1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }
}