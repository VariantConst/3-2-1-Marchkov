package com.variantconst.marchkov.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*
import com.variantconst.marchkov.utils.*

class ReservationManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleCookieJar())
        .build()

    private val gson = Gson()

    @RequiresApi(Build.VERSION_CODES.O)
    fun performLogin(
        username: String,
        password: String,
        isToYanyuan: Boolean,
        updateLoadingMessage: (String) -> Unit,
        callback: (Boolean, String, Bitmap?, Map<String, Any>?, String?) -> Unit,
        timeoutJob: Job?
    ) {
        performLoginWithClient(username, password, isToYanyuan, client, updateLoadingMessage, callback, timeoutJob)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun performLoginWithClient(
        username: String,
        password: String,
        isToYanyuan: Boolean,
        client: OkHttpClient,
        updateLoadingMessage: (String) -> Unit,
        callback: (Boolean, String, Bitmap?, Map<String, Any>?, String?) -> Unit,
        timeoutJob: Job?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateLoadingMessage("正在登录...")
                var request: Request

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

                var response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "No response body"
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val jsonMap: Map<String, Any> = gson.fromJson(responseBody, mapType)
                val token = jsonMap["token"] as? String ?: "Token not found"
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && token.isNotEmpty()) {
                        callback(true, "第一步：登录账号成功\n获取 token 为 $token", null, null, null)
                    } else {
                        callback(false, "第一步：登录账号失败\n获取 token 为 $token", null, null, null)
                    }
                }

                val urlWithToken = "https://wproc.pku.edu.cn/site/login/cas-login?redirect_url=https://wproc.pku.edu.cn/v2/reserve/&token=$token"
                request = Request.Builder()
                    .url(urlWithToken)
                    .build()

                response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        callback(true, "第二步：跟随重定向成功\n结果：${response.code}", null, null, null)
                    } else {
                        callback(false, "第二步：跟随重定向失败\n结果：${response.code}", null, null, null)
                    }
                }

                updateLoadingMessage("正在获取预约列表...")
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
                        callback(true, "第三步：获取班车信息成功\n共获取 ${resourceList.size} 条班车信息", null, null, null)
                    } else {
                        callback(false, "第三步：获取班车信息失败", null, null, null)
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
                        callback(false, "没有找到可约的班车", null, null, null)
                        updateLoadingMessage("")
                    }
                    return@launch
                }

                if (isTemp) {
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
                            val creatorNameFull = (qrCodeJson["d"] as? Map<*, *>)?.get("name") as? String
                            val creatorName = creatorNameFull?.split("\r\n")?.get(0) ?: "马池口🐮🐴"
                            val creatorDepart = creatorNameFull?.split("\r\n")?.get(2) ?: "这个需要你自己衡量！"
                            saveRealName(creatorName)
                            saveDepartment(creatorDepart)
                            val reservationDetails = mapOf<String, Any>(
                                "creator_name" to (creatorName),
                                "resource_name" to resourceName,
                                "start_time" to startTime,
                                "is_temp" to true
                            )
                            if (qrCodeData != null) {
                                try {
                                    val qrCodeBitmap = generateQRCode(qrCodeData)
                                    callback(true, "成功获取临时码", qrCodeBitmap, reservationDetails, qrCodeData)
                                } catch (e: IllegalArgumentException) {
                                    callback(false, "无法解码临时码字符串: ${e.message}", null, null, qrCodeData)
                                }
                            } else {
                                callback(false, "找不到临时码字符串", null, null, null)
                            }
                        } else {
                            callback(false, "临时码请求响应为: $tempQrCodeResponse", null, null, null)
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
                            callback(true, "第四步：预约班车成功\n响应为 $launchResponse", null, null, null)
                        } else {
                            callback(false, "第四步：预约班车失败\n响应为 $launchResponse", null, null, null)
                        }
                    }

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
                                val creatorName = reservation["creator_name"] as? String ?: "马池口🐮🐴"
                                val creatorDepart = reservation["creator_depart"] as? String ?: "这个需要你自己衡量！"
                                saveRealName(creatorName)
                                saveDepartment(creatorDepart)
                                reservationDetails = mapOf<String, Any>(
                                    "creator_name" to creatorName,
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
                            callback(true, "第五步：获取已约班车信息成功\n响应：$formattedJson", null, reservationDetails, null)
                        } else {
                            callback(false, "第五步：获取已约班车信息失败\n响应：$formattedJson", null, null, null)
                        }
                    }

                    updateLoadingMessage("正在生成二维码...")
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
                                callback(true, "正在处理第 ${index + 1} 个预约:", null, null, null)
                                callback(true, "  App ID: $appId", null, null, null)
                                callback(true, "  Appointment ID: $appAppointmentId", null, null, null)
                            }

                            val qrCodeUrl = "https://wproc.pku.edu.cn/site/reservation/get-sign-qrcode?type=0&id=$appId&hall_appointment_data_id=$appAppointmentId"
                            request = Request.Builder()
                                .url(qrCodeUrl)
                                .build()

                            response = client.newCall(request).execute()
                            val qrCodeResponse = response.body?.string() ?: "No response body"
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful) {
                                    callback(true, " 乘车码响应: $qrCodeResponse", null, null, null)

                                    val qrCodeJson = gson.fromJson(qrCodeResponse, Map::class.java)
                                    val qrCodeData = (qrCodeJson["d"] as? Map<*, *>)?.get("code") as? String
                                    if (qrCodeData != null) {
                                        withContext(Dispatchers.Main) {
                                            callback(true, "要解码的乘车码字符串: $qrCodeData", null, null, qrCodeData)
                                        }
                                        try {
                                            val qrCodeBitmap = generateQRCode(qrCodeData)
                                            callback(true, "乘车码解码成功", qrCodeBitmap, reservationDetails, qrCodeData)
                                        } catch (e: IllegalArgumentException) {
                                            withContext(Dispatchers.Main) {
                                                callback(false, "无法解码乘车码字符串: ${e.message}", null, null, qrCodeData)
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            callback(false, "找不到乘车码", null, null, null)
                                        }
                                    }
                                } else {
                                    callback(false, "乘车码请求响应: $qrCodeResponse", null, null, null)
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            callback(false, "找不到预约信息。可能是时间太早还无法查看乘车码。", null, null, null)
                        }
                    }
                }
                updateLoadingMessage("")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, "无法执行请求: ${e.message}", null, null, null)
                    callback(false, "Stack trace: ${e.stackTraceToString()}", null, null, null)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    cancelLoadingTimeout(timeoutJob)
                }
            }
        }
    }

    private fun saveRealName(realName: String) {
        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentRealName = sharedPreferences.getString("realName", null)
        if (currentRealName == null || realName == "马池口🐮🐴") {
            with(sharedPreferences.edit()) {
                putString("realName", realName)
                apply()
            }
        }
    }

    private fun saveDepartment(department: String) {
        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentDepartment = sharedPreferences.getString("department", null)
        if (currentDepartment == null || department == "这个需要你自己衡量！") {
            with(sharedPreferences.edit()) {
                putString("department", department)
                apply()
            }
        }
    }

    private fun cancelLoadingTimeout(job: Job?) {
        job?.cancel()
    }
}

data class BusInfo(
    val resourceId: Int,
    val resourceName: String,
    val startTime: String,
    val isTemp: Boolean,
    val period: Int?
)
