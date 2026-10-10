package net.extrawdw.apps.miuisucks.notificationchannel;
interface IChannelService {
    String listApps() = 0;
    String inspect(String packageName) = 1;
    String repairApp(String snapshot) = 2;
    String repairChannel(String snapshot, String channelId) = 3;
    String restoreChannel(String snapshot, String channelId) = 4;
    void cancel() = 5;
    void returnToApp() = 6;
    void destroy() = 16777114;
}
