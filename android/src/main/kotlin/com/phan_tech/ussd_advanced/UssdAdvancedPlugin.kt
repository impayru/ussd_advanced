package com.phan_tech.ussd_advanced

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.UssdResponseCallback
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StringCodec
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.CompletableFuture

/** UssdAdvancedPlugin */
class UssdAdvancedPlugin :
  FlutterPlugin,
  MethodCallHandler,
  ActivityAware,
  BasicMessageChannel.MessageHandler<String?> {

  // Channel for method calls from Dart
  private lateinit var channel: MethodChannel

  // Channel for interactive multi-session USSD replies
  private lateinit var basicMessageChannel: BasicMessageChannel<String>

  // Android context / activity references
  private var context: Context? = null
  private var activity: Activity? = null
  private var senderActivity: Activity? = null

  // Internal helpers
  private val ussdApi: USSDApi = USSDController
  private var event: AccessibilityEvent? = null

  /** FlutterPlugin **/
  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(
      binding.binaryMessenger,
      "method.com.phan_tech/ussd_advanced"
    )
    channel.setMethodCallHandler(this)

    basicMessageChannel = BasicMessageChannel(
      binding.binaryMessenger,
      "message.com.phan_tech/ussd_advanced",
      StringCodec.INSTANCE
    )
    // handler ставим динамически через setListener() только при старте мультисессии
    this.context = binding.applicationContext
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    basicMessageChannel.setMessageHandler(null)
    context = null
  }

  /** ActivityAware **/
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    activity = null
  }

  override fun onDetachedFromActivityForConfigChanges() {
    senderActivity = null
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    senderActivity = binding.activity
    activity = binding.activity
  }

  /** BasicMessageChannel handler setup **/
  private fun setListener() {
    basicMessageChannel.setMessageHandler(this)
  }

  override fun onMessage(message: String?, reply: BasicMessageChannel.Reply<String?>) {
    if (message != null) {
      USSDController.send2(message, event!!) {
        event = AccessibilityEvent.obtain(it)
        try {
          if (it.text.isNotEmpty()) {
            reply.reply(it.text.first().toString())
          } else {
            reply.reply(null)
          }
        } catch (e: Exception) {
          // swallow
        }
      }
    }
  }

  /** MethodChannel.MethodCallHandler **/
  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    var subscriptionId: Int = 1
    var code: String? = ""

    if (call.method == "sendUssd" ||
      call.method == "sendAdvancedUssd" ||
      call.method == "multisessionUssd"
    ) {
      val subscriptionIdInteger = call.argument<Int>("subscriptionId")
        ?: throw RequestParamsException(
          "Incorrect parameter type: `subscriptionId` must be an int"
        )
      subscriptionId = subscriptionIdInteger
      if (subscriptionId < -1) {
        throw RequestParamsException(
          "Incorrect parameter value: `subscriptionId` must be >= -1"
        )
      }
      code = call.argument<String>("code")
      if (code == null) {
        throw RequestParamsException("Incorrect parameter type: `code` must be a String")
      }
      if (code.isEmpty()) {
        throw RequestParamsException(
          "Incorrect parameter value: `code` must not be an empty string"
        )
      }
    }

    when (call.method) {
      "sendUssd" -> {
        result.success(defaultUssdService(code!!, subscriptionId))
      }

      "sendAdvancedUssd" -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val res = singleSessionUssd(code!!, subscriptionId)
          if (res != null) {
            res.exceptionally { e: Throwable? ->
              if (e is RequestExecutionException) {
                result.error(
                  RequestExecutionException.type, e.message, null
                )
              } else {
                result.error(RequestExecutionException.type, e?.message, null)
              }
              null
            }.thenAccept(result::success)
          } else {
            result.success(res)
          }
        } else {
          result.success(defaultUssdService(code!!, subscriptionId))
        }
      }

      "multisessionUssd" -> {
        // runtime permission checks
        if (ContextCompat.checkSelfPermission(
            context!!,
            android.Manifest.permission.CALL_PHONE
          ) != PackageManager.PERMISSION_GRANTED
        ) {
          if (!ActivityCompat.shouldShowRequestPermissionRationale(
              activity!!,
              android.Manifest.permission.CALL_PHONE
            )
          ) {
            ActivityCompat.requestPermissions(
              activity!!,
              arrayOf(android.Manifest.permission.CALL_PHONE),
              2
            )
          }
        } else if (
          ContextCompat.checkSelfPermission(
            context!!,
            android.Manifest.permission.READ_PHONE_STATE
          ) != PackageManager.PERMISSION_GRANTED
        ) {
          if (!ActivityCompat.shouldShowRequestPermissionRationale(
              activity!!,
              android.Manifest.permission.READ_PHONE_STATE
            )
          ) {
            ActivityCompat.requestPermissions(
              activity!!,
              arrayOf(android.Manifest.permission.READ_PHONE_STATE),
              2
            )
          }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          multisessionUssd(code!!, subscriptionId, result)
        } else {
          result.success(defaultUssdService(code!!, subscriptionId))
        }
      }

      "multisessionUssdCancel" -> {
        multisessionUssdCancel()
        result.success(null)
      }

      else -> {
        result.notImplemented()
      }
    }
  }

  /** Exceptions **/
  private class RequestExecutionException internal constructor(
    override var message: String
  ) : Exception() {
    companion object {
      var type = "ussd_plugin_ussd_execution_failure"
    }
  }

  private class RequestParamsException internal constructor(
    override var message: String
  ) : Exception() {
    companion object {
      var type = "ussd_plugin_incorrect__parameters"
    }
  }

  /** Android 8+ direct USSD session (single shot) **/
  private fun singleSessionUssd(
    ussdCode: String,
    subscriptionId: Int
  ): CompletableFuture<String>? {
    val useDefaultSim: Boolean = subscriptionId == -1

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val res: CompletableFuture<String> = CompletableFuture()

      // CALL_PHONE permission
      if (ContextCompat.checkSelfPermission(
          context!!,
          android.Manifest.permission.CALL_PHONE
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        if (!ActivityCompat.shouldShowRequestPermissionRationale(
            activity!!,
            android.Manifest.permission.CALL_PHONE
          )
        ) {
          ActivityCompat.requestPermissions(
            activity!!,
            arrayOf(android.Manifest.permission.CALL_PHONE),
            2
          )
        }
      }

      // base TelephonyManager
      val tm =
        context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

      val simManager: TelephonyManager = tm.createForSubscriptionId(subscriptionId)

      val callback = object : UssdResponseCallback() {
        override fun onReceiveUssdResponse(
          telephonyManager: TelephonyManager,
          request: String,
          response: CharSequence
        ) {
          res.complete(response.toString())
        }

        override fun onReceiveUssdResponseFailed(
          telephonyManager: TelephonyManager,
          request: String,
          failureCode: Int
        ) {
          when (failureCode) {
            TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> {
              res.completeExceptionally(
                RequestExecutionException("USSD_ERROR_SERVICE_UNAVAIL")
              )
            }

            TelephonyManager.USSD_RETURN_FAILURE -> {
              res.completeExceptionally(
                RequestExecutionException("USSD_RETURN_FAILURE")
              )
            }

            else -> {
              res.completeExceptionally(
                RequestExecutionException("unknown error")
              )
            }
          }
        }
      }

      if (useDefaultSim) {
        tm.sendUssdRequest(
          ussdCode,
          callback,
          Handler(Looper.getMainLooper())
        )
      } else {
        simManager.sendUssdRequest(
          ussdCode,
          callback,
          Handler(Looper.getMainLooper())
        )
      }

      return res
    } else {
      // SDK < 26 fallback
      defaultUssdService(ussdCode, subscriptionId)
      return null
    }
  }

  /** Multi-session mode via accessibility (older-style interactive USSD menus) **/
  private fun multisessionUssd(
    ussdCode: String,
    subscriptionId: Int,
    @NonNull result: Result
  ) {
    var slot = subscriptionId
    if (subscriptionId == -1) {
      slot = 0
    }

    ussdApi.callUSSDInvoke(
      activity!!,
      ussdCode,
      slot,
      object : USSDController.CallbackInvoke {

        override fun responseInvoke(ev: AccessibilityEvent) {
          event = AccessibilityEvent.obtain(ev)
          setListener()

          try {
            if (ev.text.isNotEmpty()) {
              result.success(ev.text.first().toString())
            } else {
              result.success(null)
            }
          } catch (e: Exception) {
            // swallow
          }
        }

        override fun over(message: String) {
          try {
            basicMessageChannel.setMessageHandler(null)
            basicMessageChannel.send(message)
            result.success(message)
          } catch (e: Exception) {
            // swallow
          }
        }
      })
  }

  private fun multisessionUssdCancel() {
    if (event != null) {
      basicMessageChannel.setMessageHandler(null)
      ussdApi.cancel2(event!!)
      event = null
    }
  }

  // possible vendor-specific SIM slot extras for ACTION_CALL intent routing
  private val simSlotName = arrayOf(
    "extra_asus_dial_use_dualsim",
    "com.android.phone.extra.slot",
    "slot",
    "simslot",
    "sim_slot",
    "subscription",
    "Subscription",
    "phone",
    "com.android.phone.DialingMode",
    "simSlot",
    "slot_id",
    "simId",
    "simnum",
    "phone_type",
    "slotId",
    "slotIdx"
  )

  /** Generic USSD dialer fallback using ACTION_CALL / ACTION_VIEW **/
  private fun defaultUssdService(
    ussdCode: String,
    subscriptionId: Int
  ) {
    if (ContextCompat.checkSelfPermission(
        context!!,
        android.Manifest.permission.CALL_PHONE
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      if (!ActivityCompat.shouldShowRequestPermissionRationale(
          activity!!,
          android.Manifest.permission.CALL_PHONE
        )
      ) {
        ActivityCompat.requestPermissions(
          activity!!,
          arrayOf(android.Manifest.permission.CALL_PHONE),
          2
        )
      }
    }

    try {
      val useDefaultSim: Boolean = subscriptionId == -1
      val sim: Int = subscriptionId - 1

      var number: String = ussdCode.replace("#", "%23")
      if (!number.startsWith("tel:")) {
        number = "tel:$number"
      }

      val intent = Intent(
        if (isTelephonyEnabled())
          Intent.ACTION_CALL
        else
          Intent.ACTION_VIEW
      )
      intent.data = Uri.parse(number)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

      if (!useDefaultSim) {
        intent.putExtra("com.android.phone.force.slot", true)
        intent.putExtra("Cdma_Supp", true)

        for (s in simSlotName) {
          intent.putExtra(s, sim)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          if (ContextCompat.checkSelfPermission(
              context!!,
              android.Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
          ) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                activity!!,
                android.Manifest.permission.READ_PHONE_STATE
              )
            ) {
              ActivityCompat.requestPermissions(
                activity!!,
                arrayOf(android.Manifest.permission.READ_PHONE_STATE),
                2
              )
            }
          }
          val telecomManager =
            context!!.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

          val phoneAccountHandleList = telecomManager.callCapablePhoneAccounts
          if (phoneAccountHandleList != null && phoneAccountHandleList.isNotEmpty()) {
            intent.putExtra(
              "android.telecom.extra.PHONE_ACCOUNT_HANDLE",
              phoneAccountHandleList[sim]
            )
          }
        }
      }

      context!!.startActivity(intent)
    } catch (e: Exception) {
      throw e
    }
  }

  private fun isTelephonyEnabled(): Boolean {
    val tm =
      context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    return tm.phoneType != TelephonyManager.PHONE_TYPE_NONE
  }
}
