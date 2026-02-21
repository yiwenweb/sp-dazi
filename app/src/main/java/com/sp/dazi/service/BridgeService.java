package com.sp.dazi.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.sp.dazi.App;
import com.sp.dazi.MainActivity;
import com.sp.dazi.R;
import com.sp.dazi.model.NaviData;
import com.sp.dazi.receiver.AmapNaviReceiver;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 桥接前台服务
 *
 * 功能：
 * 1. 监听 UDP 7705 端口，接收 C3 设备广播（自动发现）
 * 2. 每 200ms 通过 UDP 7706 向 C3 发送导航 JSON 数据
 * 3. 管理连接状态
 */
public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    private static final int NOTIFICATION_ID = 1;

    // C3 自动发现端口
    private static final int DISCOVERY_PORT = 7705;
    // C3 数据接收端口
    private static final int DATA_PORT = 7706;
    // 数据发送间隔 ms
    private static final long SEND_INTERVAL = 200;

    // 连接状态
    public enum ConnectionState {
        SEARCHING,   // 搜索设备中
        CONNECTED,   // 已连接
        DISCONNECTED // 未连接
    }

    public interface StateCallback {
        void onStateChanged(ConnectionState state, String c3Ip);
        void onDataSent(int packetCount);
    }

    private final IBinder binder = new LocalBinder();
    private StateCallback stateCallback;

    private volatile boolean running = false;
    private volatile String c3IpAddress = null;
    private volatile ConnectionState connectionState = ConnectionState.SEARCHING;
    private int packetCount = 0;

    private Thread discoveryThread;
    private Timer sendTimer;
    private DatagramSocket sendSocket;
    private AmapNaviReceiver dynamicReceiver;

    public class LocalBinder extends Binder {
        public BridgeService getService() {
            return BridgeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 检查是否手动设置了 C3 IP
        if (intent != null && intent.hasExtra("c3_ip")) {
            String ip = intent.getStringExtra("c3_ip");
            if (ip != null && !ip.isEmpty()) {
                c3IpAddress = ip;
                setConnectionState(ConnectionState.CONNECTED);
                Log.i(TAG, "手动设置 C3 IP: " + ip);
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("SP搭子运行中"));
        startBridge();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    public void setStateCallback(StateCallback callback) {
        this.stateCallback = callback;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public String getC3IpAddress() {
        return c3IpAddress;
    }

    public int getPacketCount() {
        return packetCount;
    }

    /** 手动设置 C3 IP 地址 */
    public void setC3Ip(String ip) {
        if (ip != null && !ip.isEmpty()) {
            c3IpAddress = ip;
            setConnectionState(ConnectionState.CONNECTED);
            Log.i(TAG, "手动设置 C3 IP: " + ip);
        }
    }

    private void startBridge() {
        if (running) return;
        running = true;

        // 动态注册高德广播接收器（Android 8.0+ 静态注册收不到隐式广播）
        dynamicReceiver = new AmapNaviReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.autonavi.minimap.broadcast.CYCLIC_NAVI_INFO");
        filter.addAction("com.autonavi.minimap.broadcast.NAVI_INFO");
        filter.addAction("AUTONAVI_STANDARD_BROADCAST_RECV");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dynamicReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(dynamicReceiver, filter);
        }
        Log.i(TAG, "已动态注册高德广播接收器");

        // 注册高德广播回调
        AmapNaviReceiver.setCallback(data -> {
            // 收到新导航数据，不需要额外处理，sendTimer 会定时读取
        });

        // 启动 C3 自动发现线程
        discoveryThread = new Thread(this::discoveryLoop, "C3-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        // 启动定时发送（200ms 间隔）
        sendTimer = new Timer("DataSender", true);
        sendTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendNaviData();
            }
        }, 1000, SEND_INTERVAL);

        Log.i(TAG, "桥接服务已启动");
    }

    private void stopBridge() {
        running = false;
        AmapNaviReceiver.setCallback(null);

        // 注销动态广播接收器
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (Exception e) {
                Log.w(TAG, "注销广播接收器异常", e);
            }
            dynamicReceiver = null;
        }

        if (sendTimer != null) {
            sendTimer.cancel();
            sendTimer = null;
        }
        if (sendSocket != null && !sendSocket.isClosed()) {
            sendSocket.close();
        }
        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        Log.i(TAG, "桥接服务已停止");
    }

    /**
     * C3 自动发现：监听 UDP 7705 端口
     * C3 设备会定期在此端口广播自己的存在
     */
    private void discoveryLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setSoTimeout(5000);
            socket.setReuseAddress(true);
            byte[] buf = new byte[1024];
            Log.i(TAG, "开始监听 C3 广播 (UDP " + DISCOVERY_PORT + ")");

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String senderIp = packet.getAddress().getHostAddress();
                    String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                    Log.d(TAG, "收到 C3 广播: " + msg + " from " + senderIp);

                    // 收到广播即认为发现了 C3
                    if (c3IpAddress == null || !c3IpAddress.equals(senderIp)) {
                        c3IpAddress = senderIp;
                        setConnectionState(ConnectionState.CONNECTED);
                    }
                } catch (SocketTimeoutException e) {
                    // 超时无广播，如果之前没有手动设置 IP 且没发现过设备
                    if (c3IpAddress == null) {
                        setConnectionState(ConnectionState.SEARCHING);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "发现线程异常", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * 发送导航数据到 C3 (UDP 7706)
     */
    private void sendNaviData() {
        if (c3IpAddress == null) return;

        try {
            NaviData data = AmapNaviReceiver.getCurrentData();
            JSONObject json = data.toJson();
            byte[] bytes = json.toString().getBytes("UTF-8");

            if (sendSocket == null || sendSocket.isClosed()) {
                sendSocket = new DatagramSocket();
            }

            InetAddress addr = InetAddress.getByName(c3IpAddress);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, DATA_PORT);
            sendSocket.send(packet);

            packetCount++;
            if (stateCallback != null) {
                stateCallback.onDataSent(packetCount);
            }

            // 每 50 个包打一次日志
            if (packetCount % 50 == 0) {
                Log.d(TAG, "已发送 " + packetCount + " 个数据包到 " + c3IpAddress);
            }
        } catch (Exception e) {
            Log.e(TAG, "发送数据失败", e);
            // 连续失败可能是 C3 断开了
            setConnectionState(ConnectionState.DISCONNECTED);
        }
    }

    private void setConnectionState(ConnectionState state) {
        if (connectionState != state) {
            connectionState = state;
            Log.i(TAG, "连接状态: " + state + (c3IpAddress != null ? " (" + c3IpAddress + ")" : ""));
            if (stateCallback != null) {
                stateCallback.onStateChanged(state, c3IpAddress);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("SP搭子")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
