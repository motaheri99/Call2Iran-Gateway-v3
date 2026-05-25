package com.calliran.gateway.notification

object SmsTemplates {

    fun preCallSms(callerNumber: String): String {
        return "سلام\n" +
            "یکی از عزیزانتون به شماره $callerNumber داره برای تماس با شما از سرویس Calliran.app استفاده می‌کنه.\n" +
            "\n" +
            "تا چند لحظه دیگه، با همین شماره بهتون زنگ می‌زنیم، لطفاً پاسخ بدید و منتظر بمونید تا عزیزتون به تماس اضافه بشه.\n" +
            "\n" +
            "این تماس برای شما هزینه‌ای نداره."
    }
}
