package com.example.greetingcard

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import com.example.greetingcard.components.SettingsDialog
import com.example.greetingcard.components.LogScreen
import com.example.greetingcard.components.LoginScreen
import com.example.greetingcard.components.MainPagerScreen
import com.example.greetingcard.utils.util.*
import com.example.greetingcard.utils.SimpleCookieJar
import com.example.greetingcard.utils.Settings

@RequiresApi(Build.VERSION_CODES.O)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 加载设置
        Settings.load(this)

        val sharedPreferences = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val savedUsername = sharedPreferences.getString("username", null)
        val savedPassword = sharedPreferences.getString("password", null)

        setContent {
            var responseTexts by remember { mutableStateOf(listOf<String>()) }
            var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var reservationDetails by remember { mutableStateOf<Map<String, Any>?>(null) }
            var qrCodeString by remember { mutableStateOf<String?>(null) }
            var isLoggedIn by remember { mutableStateOf(false) }
            var showLoading by remember { mutableStateOf(true) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var isToYanyuan by remember { mutableStateOf(getInitialDirection()) }
            var showSnackbar by remember { mutableStateOf(false) }
            var snackbarMessage by remember { mutableStateOf("") }
            var showLogs by remember { mutableStateOf(false) }
            var showSettingsDialog by remember { mutableStateOf(false) }
            var currentPage by remember { mutableIntStateOf(0) }
            var isReservationLoaded by remember { mutableStateOf(false) }
            var isReservationLoading by remember { mutableStateOf(false) }
            var loadingMessage by remember { mutableStateOf("") }

            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                if (savedUsername != null && savedPassword != null) {
                    val firstAttemptSuccess = performLoginAndHandleResult(
                        username = savedUsername,
                        password = savedPassword,
                        isToYanyuan = isToYanyuan,
                        updateLoadingMessage = { message -> loadingMessage = message },
                        handleResult = { success, response, bitmap, details, qrCode ->
                            responseTexts = responseTexts + response
                            Log.v("Mytag", "response is $response and success is $success")
                            if (success) {
                                isLoggedIn = true
                                showLoading = details == null
                                isReservationLoaded = details != null
                                currentPage = 0
                                qrCodeBitmap = bitmap
                                reservationDetails = details
                                qrCodeString = qrCode
                            } else {
                                errorMessage = response
                                showLoading = false
                            }
                        }
                    )
                    Log.v("Mytag", "firstAttemptSuccess is $firstAttemptSuccess")

                    if (!isLoggedIn) {
                        errorMessage = "当前时段无车可坐！"
                        showLoading = false
                    }
                } else {
                    showLoading = false
                }
            }

            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(visible = showLoading || loadingMessage.isNotEmpty()) {
                            LoadingScreen(message = loadingMessage)
                        }

                        if (!showLoading && loadingMessage.isEmpty()) {
                            if (isLoggedIn) {
                                if (showLogs) {
                                    LogScreen(
                                        responseTexts = responseTexts,
                                        onBack = {
                                            showLogs = false
                                            currentPage = 1 // 返回时设置页码为第二屏
                                        }
                                    )
                                } else {
                                    MainPagerScreen(
                                        qrCodeBitmap = qrCodeBitmap,
                                        reservationDetails = reservationDetails,
                                        onLogout = {
                                            isLoggedIn = false
                                            responseTexts = listOf()
                                            qrCodeBitmap = null
                                            reservationDetails = null
                                            qrCodeString = null
                                            clearLoginInfo()
                                        },
                                        onToggleBusDirection = {
                                            isToYanyuan = !isToYanyuan
                                            isReservationLoading = true
                                            scope.launch {
                                                val sessionCookieJar = SimpleCookieJar()
                                                val client = OkHttpClient.Builder()
                                                    .cookieJar(sessionCookieJar)
                                                    .build()

                                                performLoginWithClient(
                                                    username = savedUsername ?: "",
                                                    password = savedPassword ?: "",
                                                    isToYanyuan = isToYanyuan,
                                                    client = client,
                                                    updateLoadingMessage = { message ->
                                                        loadingMessage = message
                                                    },
                                                    callback = { success, response, bitmap, details, qrCode ->
                                                        responseTexts = responseTexts + response
                                                        if (success) {
                                                            qrCodeBitmap = bitmap
                                                            reservationDetails = details
                                                            qrCodeString = qrCode
                                                            snackbarMessage = "反向预约成功"
                                                        } else {
                                                            snackbarMessage = "反向无车可坐"
                                                        }
                                                        isReservationLoading = false
                                                        showSnackbar = true
                                                    }
                                                )
                                            }
                                        },
                                        onShowLogs = { showLogs = true },
                                        onEditSettings = { showSettingsDialog = true },
                                        currentPage = currentPage,
                                        setPage = { currentPage = it },
                                        isReservationLoading = isReservationLoading
                                    )
                                }
                            } else {
                                errorMessage?.let { msg ->
                                    ErrorScreen(message = msg, onRetry = {
                                        errorMessage = null
                                        showLoading = true
                                        scope.launch {
                                            performLogin(
                                                username = savedUsername ?: "",
                                                password = savedPassword ?: "",
                                                isToYanyuan = isToYanyuan,
                                                updateLoadingMessage = { message ->
                                                    loadingMessage = message
                                                },
                                                callback = { success, response, bitmap, details, qrCode ->
                                                    responseTexts = responseTexts + response
                                                    if (success) {
                                                        isLoggedIn = true
                                                        showLoading = false
                                                        currentPage = 0
                                                        qrCodeBitmap = bitmap
                                                        reservationDetails = details
                                                        qrCodeString = qrCode
                                                    } else {
                                                        errorMessage = response
                                                        showLoading = false
                                                    }
                                                }
                                            )
                                        }
                                    })
                                } ?: LoginScreen(
                                    onLogin = { username, password ->
                                        showLoading = true
                                        performLogin(
                                            username = username,
                                            password = password,
                                            isToYanyuan = isToYanyuan,
                                            updateLoadingMessage = { message ->
                                                loadingMessage = message
                                            },
                                            callback = { success, response, bitmap, details, qrCode ->
                                                responseTexts = responseTexts + response
                                                if (success) {
                                                    isLoggedIn = true
                                                    showLoading = false
                                                    saveLoginInfo(username, password)
                                                    currentPage = 0
                                                    qrCodeBitmap = bitmap
                                                    reservationDetails = details
                                                    qrCodeString = qrCode
                                                } else {
                                                    errorMessage = response
                                                    showLoading = false
                                                }
                                            }
                                        )
                                    }
                                )
                            }
                        }

                        LaunchedEffect(showSnackbar) {
                            if (showSnackbar) {
                                delay(1000)
                                showSnackbar = false
                            }
                        }

                        if (showSnackbar) {
                            Snackbar(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.BottomCenter)
                                    .defaultMinSize(minWidth = 150.dp),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(snackbarMessage, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }

                        if (showSettingsDialog) {
                            SettingsDialog(
                                onDismiss = { showSettingsDialog = false },
                                onSave = { prevInterval, nextInterval, criticalTime ->
                                    Settings.updatePrevInterval(context, prevInterval)
                                    Settings.updateNextInterval(context, nextInterval)
                                    Settings.updateCriticalTime(context, criticalTime)
                                },
                                initialPrevInterval = Settings.PREV_INTERVAL,
                                initialNextInterval = Settings.NEXT_INTERVAL,
                                initialCriticalTime = Settings.CRITICAL_TIME
                            )
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun performLoginAndHandleResult(
        username: String,
        password: String,
        isToYanyuan: Boolean,
        updateLoadingMessage: (String) -> Unit,
        handleResult: (Boolean, String, Bitmap?, Map<String, Any>?, String?) -> Unit
    ): Boolean {
        val deferredResult = CompletableDeferred<Boolean>()

        performLogin(username, password, isToYanyuan, updateLoadingMessage) { success, response, bitmap, details, qrCode ->
            handleResult(success, response, bitmap, details, qrCode)
            deferredResult.complete(success)
        }

        return deferredResult.await()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun performLogin(
        username: String,
        password: String,
        isToYanyuan: Boolean,
        updateLoadingMessage: (String) -> Unit,
        callback: (Boolean, String, Bitmap?, Map<String, Any>?, String?) -> Unit
    ) {
        val sessionCookieJar = SimpleCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(sessionCookieJar)
            .build()

        performLoginWithClient(username, password, isToYanyuan, client, updateLoadingMessage, callback)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun performLoginWithClient(
        username: String,
        password: String,
        isToYanyuan: Boolean,
        client: OkHttpClient,
        updateLoadingMessage: (String) -> Unit,
        callback: (Boolean, String, Bitmap?, Map<String, Any>?, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateLoadingMessage("正在登录...")
                // Step 1: GET request
                var request = Request.Builder()
                    .url("https://wproc.pku.edu.cn/api/login/main")
                    .build()
                var response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        callback(true, "Step 1: GET https://wproc.pku.edu.cn/api/login/main\n${response.code}", null, null, null)
                    } else {
                        callback(false, "Step 1: GET https://wproc.pku.edu.cn/api/login/main\n${response.code}", null, null, null)
                    }
                }

                // Step 2: POST login
                val formBody = FormBody.Builder()
                    .add("appid", "wproc")
                    .add("userName", username)
                    .add("password", password)
                    .add("redirUrl", "https://wproc.pku.edu.cn/site/login/cas-login?redirect_url=https://wproc.pku.edu.cn/v2/reserve/")
                    .build()

                request = Request.Builder()
                    .url("https://iaaa.pku.edu.cn/iaaa/oauthlogin.do")
                    .post(formBody)
                    .build()

                response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "No response body"
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val jsonMap: Map<String, Any> = gson.fromJson(responseBody, mapType)
                val token = jsonMap["token"] as? String ?: "Token not found"
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && token.isNotEmpty()) {
                        callback(true, "Step 2: POST https://iaaa.pku.edu.cn/iaaa/oauthlogin.do\nToken: $token", null, null, null)
                    } else {
                        callback(false, "Step 2: POST https://iaaa.pku.edu.cn/iaaa/oauthlogin.do\nToken: $token", null, null, null)
                    }
                }

                // Step 3: GET request with token
                val urlWithToken = "https://wproc.pku.edu.cn/site/login/cas-login?redirect_url=https://wproc.pku.edu.cn/v2/reserve/&token=$token"
                request = Request.Builder()
                    .url(urlWithToken)
                    .build()

                response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        callback(true, "Step 3: GET $urlWithToken\n${response.code}", null, null, null)
                    } else {
                        callback(false, "Step 3: GET $urlWithToken\n${response.code}", null, null, null)
                    }
                }

                updateLoadingMessage("正在获取预约列表...")
                // Step 4: GET reservation list
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val reservationListUrl = "https://wproc.pku.edu.cn/site/reservation/list-page?hall_id=1&time=$date&p=1&page_size=0"
                request = Request.Builder()
                    .url(reservationListUrl)
                    .build()

                response = client.newCall(request).execute()
                val resourcesJson = response.body?.string() ?: "No response body"
                val resourcesMap: Map<String, Any> = gson.fromJson(resourcesJson, mapType)
                val resourceList: List<*>? = (resourcesMap["d"] as? Map<*, *>)?.let { map ->
                    (map["list"] as? List<*>)
                }
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && resourceList != null) {
                        callback(true, "Step 4: GET $reservationListUrl\nResources: ${resourceList.size}", null, null, null)
                    } else {
                        callback(false, "Step 4: GET $reservationListUrl\nResources: ${resourceList?.size ?: "N/A"}", null, null, null)
                    }
                }

                Log.v("MyTag", "$resourceList")

                val chosenBus = chooseBus(resourceList, isToYanyuan)
                Log.v("MyTag", "$chosenBus and direction $isToYanyuan")
                val chosenResourceId = chosenBus.resourceId
                val chosenPeriod = chosenBus.period
                val startTime = chosenBus.startTime
                val isTemp = chosenBus.isTemp
                val resourceName = chosenBus.resourceName

                if (chosenResourceId == 0 || chosenPeriod == 0) {
                    withContext(Dispatchers.Main) {
                        callback(false, "No available bus found", null, null, null)
                        updateLoadingMessage("")
                    }
                    return@launch
                }

                // Step 5: Launch reservation
                if (isTemp) {
                    // 生成临时码
                    updateLoadingMessage("正在获取临时码...")
                    val tempQrCodeUrl = "https://wproc.pku.edu.cn/site/reservation/get-sign-qrcode?type=1&resource_id=$chosenResourceId&text=$startTime"
                    request = Request.Builder()
                        .url(tempQrCodeUrl)
                        .build()

                    response = client.newCall(request).execute()
                    val tempQrCodeResponse = response.body?.string() ?: "No response body"
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            val qrCodeJson = gson.fromJson(tempQrCodeResponse, Map::class.java)
                            val qrCodeData = (qrCodeJson["d"] as? Map<*, *>)?.get("code") as? String
                            Log.v("MyTag", "临时码响应是 is $tempQrCodeResponse")
                            val creatorNameFull = (qrCodeJson["d"] as? Map<*, *>)?.get("name") as? String
                            val creatorName = creatorNameFull?.split("\r\n")?.get(0)
                            val periodText = startTime

                            val reservationDetails = mapOf<String, Any>(
                                "creator_name" to (creatorName ?: ""),
                                "resource_name" to resourceName,
                                "start_time" to periodText,
                                "is_temp" to true
                            )
                            if (qrCodeData != null) {
                                try {
                                    val qrCodeBitmap = generateQRCode(qrCodeData)
                                    callback(true, "Temp QR Code generated", qrCodeBitmap, reservationDetails, qrCodeData)
                                } catch (e: IllegalArgumentException) {
                                    callback(false, "Failed to decode QR code: ${e.message}", null, null, qrCodeData)
                                }
                            } else {
                                callback(false, "Temp QR code data not found", null, null, null)
                            }
                        } else {
                            callback(false, "Temp QR Code response: $tempQrCodeResponse", null, null, null)
                        }
                    }
                } else {
                    val launchBody = FormBody.Builder()
                        .add("resource_id", "$chosenResourceId")
                        .add("data", "[{\"date\": \"$date\", \"period\": \"$chosenPeriod\", \"sub_resource_id\": 0}]")
                        .build()
                    request = Request.Builder()
                        .url("https://wproc.pku.edu.cn/site/reservation/launch")
                        .post(launchBody)
                        .build()

                    response = client.newCall(request).execute()
                    val launchResponse = response.body?.string() ?: "No response body"
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            callback(true, "Step 5: POST https://wproc.pku.edu.cn/site/reservation/launch\n$launchResponse", null, null, null)
                        } else {
                            callback(false, "Step 5: POST https://wproc.pku.edu.cn/site/reservation/launch\n$launchResponse", null, null, null)
                        }
                    }

                    // Step 6: GET my reservations
                    val myReservationsUrl = "https://wproc.pku.edu.cn/site/reservation/my-list-time?p=1&page_size=10&status=2&sort_time=true&sort=asc"
                    request = Request.Builder()
                        .url(myReservationsUrl)
                        .build()

                    response = client.newCall(request).execute()
                    val appsJson = response.body?.string() ?: "No response body"
                    val appsMap: Map<String, Any> = gson.fromJson(appsJson, mapType)
                    val formattedJson = formatMap(appsMap)
                    val reservationData: List<Map<String, Any>>? = (appsMap["d"] as? Map<*, *>)?.let { map ->
                        (map["data"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
                    }
                    Log.v("MyTag", "reservationData is $reservationData")
                    var reservationDetails: Map<String, Any>? = null
                    if (reservationData != null) {
                        for (reservation in reservationData) {
                            val reservationResourceId = (reservation["resource_id"] as Double).toInt()
                            Log.v("MyTag", "reservationResourceId is $reservationResourceId, and isToYanyuan is $isToYanyuan")
                            if ((reservationResourceId in listOf(2, 4) && isToYanyuan) ||
                                (reservationResourceId in listOf(5, 6, 7) && !isToYanyuan)) {
                                Log.v("MyTag", "reservationDetails is $reservation")
                                val periodText = (reservation["period_text"] as? Map<*, *>)?.values?.firstOrNull() as? Map<*, *>
                                val period = (periodText?.get("text") as? List<*>)?.firstOrNull() as? String ?: "未知时间"
                                reservationDetails = mapOf<String, Any>(
                                    "creator_name" to reservation["creator_name"] as String,
                                    "resource_name" to reservation["resource_name"] as String,
                                    "start_time" to period,
                                    "is_temp" to false
                                )
                                break
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            callback(true, "Step 6: GET $myReservationsUrl\nReservations: $formattedJson", null, reservationDetails, null)
                        } else {
                            callback(false, "Step 6: GET $myReservationsUrl\nReservations: $formattedJson", null, null, null)
                        }
                    }

                    updateLoadingMessage("正在生成二维码...")
                    // Step 7: Get QR code and cancel reservations
                    val appData: List<Map<String, Any>>? = (appsMap["d"] as? Map<*, *>)?.let { map ->
                        (map["data"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
                    }
                    withContext(Dispatchers.Main) {
                        callback(true, "Step 7: Processing ${appData?.size ?: 0} reservations", null, null, null)
                    }
                    if (appData?.isNotEmpty() == true) {
                        appData.forEachIndexed { index, app ->
                            val appId = app["id"]?.toString()?.substringBefore(".") ?: throw IllegalArgumentException("Invalid appId")
                            val appAppointmentId = app["hall_appointment_data_id"]?.toString()?.substringBefore(".") ?: throw IllegalArgumentException("Invalid appAppointmentId")

                            withContext(Dispatchers.Main) {
                                callback(true, "Processing reservation ${index + 1}:", null, null, null)
                                callback(true, "  App ID: $appId", null, null, null)
                                callback(true, "  Appointment ID: $appAppointmentId", null, null, null)
                            }

                            // Get QR code
                            val qrCodeUrl = "https://wproc.pku.edu.cn/site/reservation/get-sign-qrcode?type=0&id=$appId&hall_appointment_data_id=$appAppointmentId"
                            request = Request.Builder()
                                .url(qrCodeUrl)
                                .build()

                            response = client.newCall(request).execute()
                            val qrCodeResponse = response.body?.string() ?: "No response body"
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful) {
                                    callback(true, "  QR Code response: $qrCodeResponse", null, null, null)

                                    // Parse the QR code response and generate the QR code bitmap
                                    val qrCodeJson = Gson().fromJson(qrCodeResponse, Map::class.java)
                                    val qrCodeData = (qrCodeJson["d"] as? Map<*, *>)?.get("code") as? String
                                    if (qrCodeData != null) {
                                        withContext(Dispatchers.Main) {
                                            callback(true, "QR Code string to decode: $qrCodeData", null, null, qrCodeData)
                                        }
                                        try {
                                            val qrCodeBitmap = generateQRCode(qrCodeData)
                                            callback(true, "QR Code generated", qrCodeBitmap, reservationDetails, qrCodeData)
                                        } catch (e: IllegalArgumentException) {
                                            withContext(Dispatchers.Main) {
                                                callback(false, "Failed to decode QR code: ${e.message}", null, null, qrCodeData)
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            callback(false, "QR code data not found", null, null, null)
                                        }
                                    }
                                } else {
                                    callback(false, "QR Code response: $qrCodeResponse", null, null, null)
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            callback(true, "No reservations to process", null, null, null)
                        }
                    }
                }
                updateLoadingMessage("")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, "Failed to execute request: ${e.message}", null, null, null)
                    callback(false, "Stack trace: ${e.stackTraceToString()}", null, null, null)
                }
            }
        }
    }



    private fun saveLoginInfo(username: String, password: String) {
        val sharedPreferences = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString("username", username)
            putString("password", password)
            apply()
        }
    }

    private fun clearLoginInfo() {
        val sharedPreferences = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            remove("username")
            remove("password")
            apply()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AppTheme {
        LoginScreen(onLogin = { _, _ -> })
    }
}


data class BusInfo(
    val resourceId: Int,
    val resourceName: String,
    val startTime: String,
    val isTemp: Boolean,
    val period: Int?)
