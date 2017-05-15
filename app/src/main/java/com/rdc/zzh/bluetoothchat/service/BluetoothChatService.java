/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rdc.zzh.bluetoothchat.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothChatService {
    // Debugging
    private static final String TAG = "BluetoothChatService";
    private static final boolean D = true;

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
        UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID MY_UUID_INSECURE =
        UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private int mState;
    // 显示当前连接状态
    public static final int STATE_NONE = 0;       // 什么都不做
    public static final int STATE_LISTEN = 1;     // 监听连接
    public static final int STATE_CONNECTING = 2; // 正在建立连接
    public static final int STATE_TRANSFER = 3;  // 现在连接到一个远程的设备，可以进行传输

    //用来向主线程发送消息
    private Handler uiHandler;
    private BluetoothAdapter bluetoothAdapter;
    //用来连接端口的线程
    private AcceptThread mAcceptThread;
    private TransferThread mTransferThread;
    private ConnectThread mConnectThread;
    public BluetoothChatService(Handler uiHandler){
        this.uiHandler = uiHandler;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
    }

    /**
     * 开启服务监听
     */
    public synchronized void start(){
        if(mTransferThread != null){
            mTransferThread.cancel();
            mTransferThread = null;
        }

        setState(STATE_LISTEN);

        if(mAcceptThread == null){
            mAcceptThread = new AcceptThread(true);
            mAcceptThread.start();
        }
    }
    public void setState(int state) {
        this.mState = state;
    }

    /**
     * 连接访问
     * @param device
     */
    public synchronized void connectDevice(BluetoothDevice device) {
        Log.e(TAG, "connectDevice: ");
        // 如果有正在传输的则先关闭
        if (mState == STATE_CONNECTING) {
            if (mTransferThread != null) {mTransferThread.cancel(); mTransferThread = null;}
        }

        //如果有正在连接的则先关闭
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        //标志为正在连接
        setState(STATE_CONNECTING);
    }

    //连接等待线程
    class AcceptThread extends Thread{
        private final BluetoothServerSocket serverSocket;
        private String mSocketType;
        public AcceptThread(boolean isSecure){
            //获取服务器监听端口
            BluetoothServerSocket tmp = null;
            mSocketType = isSecure ? "Secure" : "Insecire";
            try {
                if (isSecure) {
                    tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE);
                } else {
                    tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE);
                }
            }   catch (IOException e) {
                e.printStackTrace();
            }
            serverSocket = tmp;
        }
        @Override
        public void run() {
            super.run();
            //监听端口
            Log.d(TAG, "Socket Type: " + mSocketType);
            BluetoothSocket socket = null;
            while(mState != STATE_TRANSFER) {
                try {
                    Log.e(TAG, "run: AcceptThread 阻塞调用，等待连接");
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "run: ActivityThread fail");
                }
                //获取到连接Socket后则开始通信
                if(socket != null){
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                //传输数据，服务器端调用
                                Log.e(TAG, "run: 服务器AcceptThread传输" );
                                dataTransfer(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_TRANSFER:
                                // 没有准备好或者终止连接
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel(){
            Log.e(TAG, "close: activity Thread" );
                try {
                    if(serverSocket != null)
                        serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "close: activity Thread fail");
                }
        }
    }

    /**
     * 开始连接通讯
     * @param socket
     * @param remoteDevice 远程设备
     * @param socketType
     */
    private void dataTransfer(BluetoothSocket socket, BluetoothDevice remoteDevice, String socketType) {
        //关闭连接线程，这里只能连接一个远程设备
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        // 启动管理连接线程和开启传输
        mTransferThread = new TransferThread(socket, socketType);
        mTransferThread.start();
        //标志状态为连接
        setState(STATE_TRANSFER);
    }

    /**
     * 用来传输数据的线程
     */
    class TransferThread extends Thread{
        private final BluetoothSocket socket;
        private final OutputStream out;
        private final InputStream in;
        public TransferThread(BluetoothSocket mBluetoothSocket , String socketType){
                Log.e(TAG, "TransferThread: socketType " + socketType );
                socket = mBluetoothSocket;
                OutputStream mOutputStream = null;
                InputStream mInputStream = null;
                try {
                    if(socket != null){
                        //获取连接的输入输出流
                        mOutputStream = socket.getOutputStream();
                        mInputStream = socket.getInputStream();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                out = mOutputStream;
                in = mInputStream;
        }
        @Override
        public void run() {
            super.run();
            //读取数据
            byte[] buffer = new byte[1024];
            int bytes;
            while (true){
                try {
                    bytes = in.read(buffer);
                    //TODO 分发到主线程显示
                    Log.e(TAG, "run: read " + new String(buffer , 0 , bytes) );
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "run: Transform error" );
                    //TODO 连接丢失显示并重新开始连接
                    break;
                }
            }
        }

        /**
         * 写入数据传输
         * @param buffer
         */
        public void write(byte[] buffer) {
            try {
                out.write(buffer);
                //TODO 分到到UI显示
               /* mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();*/
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                if(socket != null)
                    socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    class ConnectThread extends Thread{
        private final BluetoothSocket socket;
        private final BluetoothDevice device;
        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket mSocket = null;
            try {
                //建立通道
                mSocket = device.createRfcommSocketToServiceRecord(
                        MY_UUID_SECURE);
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "ConnectThread: fail" );
            }
            socket = mSocket;
        }

        @Override
        public void run() {
            super.run();
            //建立后取消扫描
            bluetoothAdapter.cancelDiscovery();

            try {
                Log.e(TAG, "run: connectThread 等待" );
                socket.connect();
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    socket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                    Log.e(TAG, "run: unable to close" );
                }
                //TODO 连接失败显示
            }


            // 重置
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }
            //Socket已经连接上了，默认安全,客户端才会调用
            Log.e(TAG, "run: connectThread 连接上了");
            dataTransfer(socket, device, "secure");
        }

        public void cancel(){
                try {
                    if(socket != null)
                        socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }
}
