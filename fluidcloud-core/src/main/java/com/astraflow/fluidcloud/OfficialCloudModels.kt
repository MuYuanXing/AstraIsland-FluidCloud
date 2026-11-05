package com.astraflow.fluidcloud

import android.graphics.Bitmap
import com.astraisland.events.PageNode

/**
 * 卡片里的一张图。[symbol]:卡片说明文件自带的图标(按系统符号画,不当封面、头像);
 * [animated]:动画文件([bytes] 是动画内容),[playing] 为假时停在第一帧(现行规则「系统事件接入」)。
 */
data class CloudImage(
    val key: String, val bytes: ByteArray? = null, val bitmap: Bitmap? = null, val platform: android.graphics.drawable.Icon? = null,
    val symbol: Boolean = false, val animated: Boolean = false, val playing: Boolean = true,
    /** 说明文件写明只播一遍(loop 为 false,完成对勾这类)时为假:播完停在最后一帧;没写的照旧循环 */
    val loop: Boolean = true,
    /**
     * 放在卡片所属应用自己的共享位置的图(content:// 地址,例如外卖的菜品图):在后台读,读好之前没有图;
     * 只读 [owners] 这几个应用自己的共享位置(现行规则「展开卡片总表」)。
     */
    val shared: String? = null,
    val owners: Set<String> = emptySet(),
    /** 说明文件在数据没给图时写的替代图(alt),例如外卖卡片的外卖袋图 */
    val substitute: Boolean = false,
    /** Lottie 动画循环时返回的模板原始帧。 */
    val loopStartFrame: Int? = null,
)

/**
 * 系统卡片自己登记的动作(卡片模板里的 actions,已代入这张卡片的数据,现行规则「系统事件接入」)。
 * [type] 是 message(交给提供方处理)或 deeplink(打开页面);[uri] 对 message 是提供方地址,对 deeplink 是 nativeapp:// 页面。
 * [alternatives]:登记成几种做法的列表时,这一种打不开按顺序试的其余做法(与系统卡片引擎一致)。
 */
data class CloudAction(
    val type: String,
    val uri: String,
    val method: String = "",
    val params: Map<String, String> = emptyMap(),
    val data: String? = null,
    val packageName: String? = null,
    val flag: String? = null,
    val alternatives: List<CloudAction> = emptyList(),
)

/** 系统流体云送来的一条记录里的原始事实(身份、来源、数据、显示条件),不含任何排版。 */
data class CloudSnapshot(
    val nativeKey: String,
    val userId: Int,
    val packageName: String,
    val serviceId: String,
    val instanceId: String,
    val startedAtWallMs: Long,
    val revision: Long,
    val entrance: String = "ENTRY_STATUS_BAR",
    /** 提供方随数据送来的图:图片编号或地址 → 图 */
    val images: Map<String, CloudImage> = emptyMap(),
    val notificationIds: Set<Int> = emptySet(),
    val visible: Boolean = true,
    val lockScreenAllowed: Boolean = false,
    /** 通知变成的胶囊(直接通知)的那条通知 */
    val notification: android.service.notification.StatusBarNotification? = null,
    /** 航班、火车这类出行服务直接送来画好的界面(系统卡片引擎类型 REMOTE_VIEW)所在的那份卡片数据(原样);不是这类卡片时为空 */
    val remoteData: ByteArray? = null,
    val sourceUpdatedAtMs: Long = revision,
    val ended: Boolean = false,
    /** 卡片数据里的业务字段(提供方送来的原始数据,只取第一层,值一律转成文字);模板里 {{字段}} 按它代入。 */
    val data: Map<String, String> = emptyMap(),
    /** 提供这张卡片的系统服务所在应用(系统界面托管的服务就是 com.android.systemui)。 */
    val hostPackage: String = "",
    /** 系统登记的服务显示名(例如「手电筒」);读不到时为空。 */
    val serviceName: String = "",
    /** 卡片模板的版本号,找模板文件与发动作时都要用;读不到时为 0。 */
    val templateVersion: Long = 0,
    /** 卡片当前显示的是模板里的哪一页;读不到时按首页。 */
    val pageId: String = "",
    /** 系统把这条提示标成只亮一下(即时提醒,例如勿扰、响铃模式、手势提示),亮满规定的时长就删掉(现行规则「系统事件接入」)。 */
    val instant: Boolean = false,
    /** 系统给这条提示的提醒级别(11 是短时强提醒);只亮一下的提示按它定亮多久。 */
    val remindLevel: Int = 0,
    /**
     * 卡片说明文件写的服务意图(config.json 里 intent 的 action,例如外卖是 pantanal.intent.takeout.VIEW_ORDER_PROGRESS_V3);
     * 同一个服务换了新服务号时意图不变,按它认事件种类(现行规则「系统事件接入」)。读不到时为空。
     */
    val intentAction: String = "",
    /** 这一版数据要求胶囊抖一下(提供方送来的 shouldShake,只对里程碑、即时提醒事件有效,见 OPPO 文档 13330) */
    val shake: Boolean = false,
) {
    /**
     * 事件身份,与系统流体云给这件事的编号一致(服务号、服务实例、用户、服务的时间戳,时间戳标识服务的一次生命周期)。
     * 不含来源应用:内容送到前后来源应用会从托管服务换成真正的应用,换了也还是同一件事;通知栏与状态栏的 size 不参与身份。
     */
    val key: String get() = "$userId|$serviceId|$instanceId|$startedAtWallMs"
}

enum class CloudChange { REPLACE, POST, REMOVE }

/** 一条记录:原始事实和这一页的官方内容树(模板代入数据、直接通知按官方规则读成);航班、火车另带出行服务送来的界面 [views] */
data class CloudRecord(val snapshot: CloudSnapshot, val page: PageNode, val views: OfficialRemotePage.Views? = null)
