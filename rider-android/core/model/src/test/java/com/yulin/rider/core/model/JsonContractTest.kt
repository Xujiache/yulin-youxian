package com.yulin.rider.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `device report uses backend field names`() {
        val encoded = json.encodeToString(
            DeviceReport(
                deviceId = "android-device",
                pushRegistrationId = "jpush-registration",
                pushVendor = "JPUSH",
                notificationEnabled = true,
                backgroundLocationGranted = false,
                keepaliveGuideDone = true,
            )
        )
        val fields = json.parseToJsonElement(encoded).jsonObject

        assertEquals("jpush-registration", fields.getValue("pushRegistrationId").toString().trim('"'))
        assertFalse(fields.containsKey("registrationId"))
        assertTrue(fields.containsKey("notificationEnabled"))
    }

    @Test
    fun `device report encodes app version code and managed mode`() {
        val encoded = json.encodeToString(
            DeviceReport(
                deviceId = "android-device",
                appVersionCode = 26081501,
                managedMode = "DEVICE_OWNER",
            )
        )
        val fields = json.parseToJsonElement(encoded).jsonObject
        assertEquals("26081501", fields.getValue("appVersionCode").toString())
        assertEquals("DEVICE_OWNER", fields.getValue("managedMode").toString().trim('"'))
    }

    @Test
    fun `public latest update payload decodes policy and file url`() {
        val response = json.decodeFromString<ApiResponse<AppUpdateLatest>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "policy": "OPTIONAL",
                "channel": "production",
                "versionCode": 26081501,
                "versionName": "2026.08.15.1",
                "title": "骑手端更新",
                "notes": "修复定位",
                "fileUrl": "https://hqhjxt.vip/uploads/apk/production/26081501-abcd.apk",
                "fileSize": 70123456,
                "fileSha256": "aa",
                "packageName": "com.yulin.rider",
                "certSha256": "bb",
                "minSupportedVersionCode": 0
              }
            }
            """.trimIndent()
        )
        val latest = requireNotNull(response.data)
        assertTrue(latest.isOptional)
        assertTrue(latest.available)
        assertEquals(26081501, latest.versionCode)
        assertEquals("https://hqhjxt.vip/uploads/apk/production/26081501-abcd.apk", latest.fileUrl)
    }

    @Test
    fun `message read state is derived from readAt inside PageResult`() {
        val response = json.decodeFromString<ApiResponse<PageResult<RiderMessage>>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "items": [{
                  "id": 91,
                  "messageType": "DISPATCH",
                  "title": "新单",
                  "content": "请及时处理",
                  "linkType": "TASK",
                  "linkTarget": "8801",
                  "priority": "HIGH",
                  "needVoice": true,
                  "needAck": true,
                  "ackedAt": null,
                  "readAt": "2026-08-12T03:01:02",
                  "createdAt": "2026-08-12T03:00:00"
                }],
                "total": 41,
                "page": 2,
                "pageSize": 20
              }
            }
            """.trimIndent()
        )

        val page = requireNotNull(response.data)
        assertEquals(41L, page.total)
        assertEquals(2, page.page)
        assertEquals(20, page.pageSize)
        assertTrue(page.items.single().read)
        assertEquals("8801", page.items.single().linkTarget)
    }

    @Test
    fun `task detail decodes card items events and evidences`() {
        val response = json.decodeFromString<ApiResponse<TaskDetail>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "card": {
                  "taskId": 8801,
                  "taskNo": "T-8801",
                  "orderNo": "O-1",
                  "status": "ASSIGNED",
                  "remainingSeconds": 300
                },
                "items": [{
                  "productName": "鲜牛奶",
                  "quantity": 2.0,
                  "unit": "瓶",
                  "weightKg": 1.0,
                  "amount": 2590
                }],
                "events": [{
                  "id": 9,
                  "eventType": "ASSIGN",
                  "fromStatus": "CREATED",
                  "toStatus": "ASSIGNED",
                  "operatorType": "SYSTEM",
                  "operatorName": null,
                  "reason": null,
                  "clientEventAt": null,
                  "createdAt": "2026-08-12T03:00:00"
                }],
                "evidences": [{
                  "id": 7,
                  "fileUrl": "https://cdn.example/evidence.jpg",
                  "evidenceType": "DELIVERED",
                  "capturedAt": "2026-08-12T03:03:00"
                }]
              }
            }
            """.trimIndent()
        )

        val detail = requireNotNull(response.data)
        assertEquals(8801L, detail.card.taskId)
        assertEquals("鲜牛奶", detail.items.single().productName)
        assertEquals("ASSIGN", detail.events.single().eventType)
        assertEquals(7L, detail.evidences.single().id)
    }

    @Test
    fun `shift profile and device responses match backend DTOs`() {
        val shift = json.decodeFromString<ApiResponse<ShiftCurrent>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "shiftId": 5,
                "onDuty": true,
                "onDutyAt": "2026-08-12T01:00:00",
                "onlineSeconds": 7200,
                "continuousSeconds": 3600,
                "restTotalSeconds": 300,
                "taskCount": 9,
                "deliveredCount": 7,
                "onTimeCount": 6,
                "mileageMeters": 15320,
                "earningAmount": 8800,
                "fatigue": {
                  "level": "NORMAL",
                  "needConfirm": false,
                  "forceOffDuty": false
                }
              }
            }
            """.trimIndent()
        )
        assertEquals(7200L, requireNotNull(shift.data).onlineSeconds)

        val profile = json.decodeFromString<ApiResponse<RiderProfile>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "id": 3,
                "riderNo": "R003",
                "name": "张师傅",
                "phone": "13800000000",
                "totalTaskCount": 214748,
                "locationConsentAt": "2026-08-01T12:00:00"
              }
            }
            """.trimIndent()
        )
        assertEquals(214748, requireNotNull(profile.data).totalTaskCount)

        val device = json.decodeFromString<ApiResponse<DeviceReportResponse>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "deviceId": "android-device",
                "pushRegistrationId": "registration",
                "pushVendor": "JPUSH"
              }
            }
            """.trimIndent()
        )
        assertNotNull(device.data)
        assertEquals("registration", device.data?.pushRegistrationId)
    }

    @Test
    fun `shift history has its own response shape`() {
        val history = json.decodeFromString<ApiResponse<List<ShiftHistoryItem>>>(
            """
            {
              "code": 0,
              "message": "success",
              "data": [{
                "shiftId": 5,
                "shiftDate": "2026-08-12",
                "onDutyAt": "2026-08-12T01:00:00",
                "offDutyAt": "2026-08-12T09:00:00",
                "offDutyReason": "MANUAL",
                "onlineSeconds": 28800,
                "restTotalSeconds": 600,
                "taskCount": 12,
                "deliveredCount": 11,
                "onTimeCount": 10,
                "exceptionCount": 1,
                "mileageMeters": 50200,
                "earningAmount": 12800
              }]
            }
            """.trimIndent()
        )

        assertEquals("2026-08-12", requireNotNull(history.data).single().shiftDate)
    }
}
