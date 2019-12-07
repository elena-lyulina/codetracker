import com.google.gson.GsonBuilder
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors


interface Server {
    fun getTasks() : List<Task>

    fun sendTrackingData(file: File, deleteAfter: Boolean,  postActivity: () -> Unit = { } )
}

object PluginServer : Server {
    private val daemon = Executors.newSingleThreadExecutor()

    private val diagnosticLogger: Logger = Logger.getInstance(javaClass)
    private val client = OkHttpClient()
    private val media_type_csv = "text/csv".toMediaType()
    private const val BASE_URL: String = "http://coding-assistant-helper.ru/api/"
    private const val MAX_COUNT_ATTEMPTS = 5
    private const val ACTIVITY_TRACKER_FILE = "ide-events.csv"
    private val activityTrackerPath = "${PathManager.getPluginsPath()}/activity-tracker/" + ACTIVITY_TRACKER_FILE
    // private val activityTrackerPath = "/Users/macbook/Library/Application Support/IntelliJIdea2019.2/activity-tracker/" + ACTIVITY_TRACKER_FILE
    private var activityTrackerKey: String? = null

    init {
        diagnosticLogger.info("${Plugin.PLUGIN_ID}: init server")
        diagnosticLogger.info("${Plugin.PLUGIN_ID}: Max count attempt of sending data to server = ${MAX_COUNT_ATTEMPTS}")
        initActivityTrackerInfo()
    }

    private fun sendData(request: Request): Response {
        return client.newCall(request).execute()
    }

    private fun initActivityTrackerInfo() {
        diagnosticLogger.info("${Plugin.PLUGIN_ID}: Activity tracker is working...")
        generateActivityTrackerKey()
    }

    private fun generateActivityTrackerKey() {
        val currentUrl = URL(BASE_URL + "activity-tracker-item")

        diagnosticLogger.info("${Plugin.PLUGIN_ID}: ...generating activity tracker key")

        val request = Request.Builder()
            .url(currentUrl)
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: Activity tracker key was generated successfully")
                activityTrackerKey = response.body!!.string()
            } else {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: Generating activity tracker key error")
            }
        }
    }

    var sendNextTime = true

    private fun sendDataToServer(request: Request) : CompletableFuture<Void>  {

        if (!sendNextTime) {
            return CompletableFuture.completedFuture(null)
        }

        return CompletableFuture.runAsync(Runnable {
            var curCountAttempts = 0

            while (curCountAttempts < MAX_COUNT_ATTEMPTS) {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: An attempt of sending data to server is number ${curCountAttempts + 1}")
                val code = sendData(request).code
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: HTTP status code is $code")

                //todo: replace with sendData(request).isSuccessful ?
                if (code == 200) {
                    diagnosticLogger.info("${Plugin.PLUGIN_ID}: Tracking data successfully received")
                    sendNextTime = true
                    break
                }
                curCountAttempts++
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: Error sending tracking data")
                // wait for 5 seconds
                Thread.sleep(5_000)
            }
            if (curCountAttempts == MAX_COUNT_ATTEMPTS) {
                sendNextTime = false
            }
        }, daemon)
    }

    private fun sendActivityTrackerData() {
        val file = File(activityTrackerPath)
        val fileExists = file.exists()
        if (fileExists) {
            val currentUrl = BASE_URL + "activity-tracker-item/" + activityTrackerKey

            diagnosticLogger.info("${Plugin.PLUGIN_ID}: ...sending file ${file.name}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "code", file.name,
                    RequestBody.create(media_type_csv, file)
                )

            val request = Request.Builder()
                .url(currentUrl)
                .put(requestBody.build())
                .build()

            sendDataToServer(request)
        } else {
            diagnosticLogger.info("${Plugin.PLUGIN_ID}: activity-tracker file doesn't exist")
        }
    }

    // if deleteAfter is true, the file will be deleted
    override fun sendTrackingData(file: File, deleteAfter: Boolean, postActivity: () -> Unit) {
        val currentUrl = BASE_URL + "data-item"
        diagnosticLogger.info("${Plugin.PLUGIN_ID}: ...sending file ${file.name}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "code", file.name,
                RequestBody.create(media_type_csv, file)
            )

        if (activityTrackerKey != null) {
            requestBody.addFormDataPart("activityTrackerKey", activityTrackerKey!!)
        }

        val request = Request.Builder()
            .url(currentUrl)
            .post(requestBody.build())
            .build()

        val future = sendDataToServer(request)

        future.thenRun {
            if(deleteAfter) {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: delete file ${file.name}")
                file.delete()
            }
            postActivity()
        }
        future.get()


        // todo: redundant if? cause sendActivityTrackerData calls sendDataToServer which also checks sendNextTime
        if (sendNextTime) {
            sendActivityTrackerData()
        }

        sendNextTime = true
    }

    override fun getTasks(): List<Task> {
        val currentUrl = URL(BASE_URL + "task/all")

        val request = Request.Builder().url(currentUrl).build()

        client.newCall(request).execute().use { response ->
            return if (response.code == 200) {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: All tasks successfully received")
                val gson = GsonBuilder().create()
                gson.fromJson(response.body!!.string(), Array<Task>::class.java).toList()
            } else {
                diagnosticLogger.info("${Plugin.PLUGIN_ID}: Error getting tasks")
                emptyList()
            }
        }
    }

}
