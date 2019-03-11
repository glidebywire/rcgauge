package com.pitchgauge.j9pr.pitchgauge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class BluetoothService {
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String NAME = "BluetoothData";
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_NONE = 0;
    private short IDNow;
    private short IDSave = (short) 0;
    private int SaveState = -1;
    private int ar = 16;
    private int av = 2000;
    private Context context;
    float[] fData = new float[32];
    private int iBaud = 9600;
    private int iError = 0;
    long lLastTime = System.currentTimeMillis();
    private AcceptThread mAcceptThread;
    private final BluetoothAdapter mAdapter;
    private ConnectThread mConnectThread;
    public ConnectedThread mConnectedThread;
    private final Handler mHandler;
    private int mState;
    MyFile myFile;
    private byte[] packBuffer = new byte[11];
    private Queue<Byte> queueBuffer = new LinkedList();
    private int sDataSave = 0;
    String strDate = "";
    String strTime = "";
    boolean mSuspend = false;

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = BluetoothService.this.mAdapter.listenUsingRfcommWithServiceRecord(BluetoothService.NAME, BluetoothService.MY_UUID);
            } catch (IOException e) {
            }
            this.mmServerSocket = tmp;
        }

        public void run() {
            setName("AcceptThread");
            while (BluetoothService.this.mState != 3) {
                try {
                    BluetoothSocket socket = this.mmServerSocket.accept();
                    if (socket != null) {
                        synchronized (BluetoothService.this) {
                            switch (BluetoothService.this.mState) {
                                case 0:
                                case 3:
                                    try {
                                        socket.close();
                                        break;
                                    } catch (IOException e) {
                                        break;
                                    }
                                case 1:
                                case 2:
                                    BluetoothService.this.connected(socket, socket.getRemoteDevice());
                                    break;
                            }
                        }
                    }
                } catch (IOException e2) {
                    return;
                }
            }
            return;
        }

        public void cancel() {
            try {
                this.mmServerSocket.close();
            } catch (IOException e) {
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothDevice mmDevice;
        private final BluetoothSocket mmSocket;

        public ConnectThread(BluetoothDevice device) {
            this.mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(BluetoothService.MY_UUID);
            } catch (IOException e) {
            }
            this.mmSocket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            BluetoothService.this.mAdapter.cancelDiscovery();
            try {
                this.mmSocket.connect();
                synchronized (BluetoothService.this) {
                    BluetoothService.this.mConnectThread = null;
                }
                BluetoothService.this.connected(this.mmSocket, this.mmDevice);
            } catch (IOException e) {
                BluetoothService.this.connectionFailed();
                try {
                    this.mmSocket.close();
                } catch (IOException e2) {
                }
                BluetoothService.this.start();
            }
        }

        public void cancel() {
            try {
                this.mmSocket.close();
            } catch (IOException e) {
            }
        }
    }

    class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final BluetoothSocket mmSocket;

        public ConnectedThread(BluetoothSocket socket) {
            this.mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }
            this.mmInStream = tmpIn;
            this.mmOutStream = tmpOut;
        }

        public void run() {
            byte[] tempInputBuffer = new byte[1024];
            while (true) {
                try {
                    int acceptedLen = this.mmInStream.read(tempInputBuffer);
                    if (acceptedLen > 0) {
                        BluetoothService.this.CopeSerialData(acceptedLen, tempInputBuffer);
                    }
                } catch (IOException e) {
                    BluetoothService.this.connectionLost();
                    return;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                this.mmOutStream.write(buffer);
                BluetoothService.this.mHandler.obtainMessage(3, -1, -1, buffer).sendToTarget();
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                this.mmSocket.close();
            } catch (IOException e) {
            }
        }
    }

    public BluetoothService(Context contextIn, Handler handler) {
        this.context = contextIn;
        this.mAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mState = 0;
        this.mHandler = handler;
    }

    public void Send(byte[] buffer) {
        if (this.mState == 3) {
            this.mConnectedThread.write(buffer);
        }
    }

    private synchronized void setState(int state) {
        this.mState = state;
        this.mHandler.obtainMessage(1, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return this.mState;
    }

    public synchronized void start() {
        if (this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mAcceptThread == null) {
            this.mAcceptThread = new AcceptThread();
            this.mAcceptThread.start();
        }
        setState(1);
    }

    public synchronized void connect(BluetoothDevice device) {
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        this.mConnectThread = new ConnectThread(device);
        this.mConnectThread.start();
        setState(2);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mAcceptThread != null) {
            this.mAcceptThread.cancel();
            this.mAcceptThread = null;
        }
        this.mConnectedThread = new ConnectedThread(socket);
        this.mConnectedThread.start();
        Message msg = this.mHandler.obtainMessage(4);
        Bundle bundle = new Bundle();
        bundle.putString("device_name", device.getName());
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
        setState(3);
    }

    public synchronized void stop() {
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        if (this.mAcceptThread != null) {
            this.mAcceptThread.cancel();
            this.mAcceptThread = null;
        }
        setState(0);
    }

    private void connectionFailed() {
        setState(1);
        Message msg = this.mHandler.obtainMessage(5);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "Failed to connect device");
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
    }

    private void connectionLost() {
        setState(1);
        Message msg = this.mHandler.obtainMessage(5);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "Device connection was lost");
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
    }

    public int getBaud() {
        return this.iBaud;
    }

    public void SetBaud(int iBaudrate) {
        this.iBaud = iBaudrate;
        Editor editor = this.context.getSharedPreferences("Output", 0).edit();
        editor.putString("Baud", String.format("%d", new Object[]{Integer.valueOf(iBaudrate)}));
        editor.commit();
    }

    public void ChangeBaud() {

            this.iError = 0;
    }

    public void Suspend(boolean state){
        mSuspend = state;
    }

    public void CopeSerialData(int acceptedLen, byte[] tempInputBuffer) {
        for (int i = 0; i < acceptedLen; i++) {
            this.queueBuffer.add(Byte.valueOf(tempInputBuffer[i]));
        }
        while (this.queueBuffer.size() >= 11) {
            if (((Byte) this.queueBuffer.poll()).byteValue() == (byte) 85) {
                byte sHead = ((Byte) this.queueBuffer.poll()).byteValue();
                if ((sHead & 240) == 80) {
                    this.iError = 0;
                }
                for (int j = 0; j < 9; j++) {
                    this.packBuffer[j] = ((Byte) this.queueBuffer.poll()).byteValue();
                }
                switch (sHead) {
                    case (byte) 80:
                        int ms = (((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255);
                        this.strDate = String.format("20%02d-%02d-%02d", new Object[]{Byte.valueOf(this.packBuffer[0]), Byte.valueOf(this.packBuffer[1]), Byte.valueOf(this.packBuffer[2])});
                        this.strTime = String.format(" %02d:%02d:%02d.%03d", new Object[]{Byte.valueOf(this.packBuffer[3]), Byte.valueOf(this.packBuffer[4]), Byte.valueOf(this.packBuffer[5]), Integer.valueOf(ms)});
                        //RecordData(sHead, this.strDate + this.strTime);
                        break;
                    case (byte) 81:
                        this.fData[0] = ((float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255))) / 32768.0f;
                        this.fData[1] = ((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 32768.0f;
                        this.fData[2] = ((float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255))) / 32768.0f;
                        this.fData[16] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[0])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[1])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[2])}) + " ");
                        break;
                    case (byte) 82:
                        this.fData[3] = ((float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255))) / 32768.0f;
                        this.fData[4] = ((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 32768.0f;
                        this.fData[5] = ((float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255))) / 32768.0f;
                        this.fData[16] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[3])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[4])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[5])}) + " ");
                        break;
                    case (byte) 83:
                        this.fData[6] = (((float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255))) / 32768.0f) * 180.0f;
                        this.fData[7] = (((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 32768.0f) * 180.0f;
                        this.fData[8] = (((float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255))) / 32768.0f) * 180.0f;
                        this.fData[16] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[6])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[7])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[8])}));
                        break;
                    case (byte) 84:
                        this.fData[9] = (float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255));
                        this.fData[10] = (float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255));
                        this.fData[11] = (float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255));
                        this.fData[16] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[9])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[10])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[11])}));
                        break;
                    case (byte) 85:
                        this.fData[12] = (float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255));
                        this.fData[13] = (float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255));
                        this.fData[14] = (float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255));
                        this.fData[15] = (float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255));
                        //RecordData(sHead, String.format("% 7.0f", new Object[]{Float.valueOf(this.fData[12])}) + String.format("% 7.0f", new Object[]{Float.valueOf(this.fData[13])}) + String.format("% 7.0f", new Object[]{Float.valueOf(this.fData[14])}) + String.format("% 7.0f", new Object[]{Float.valueOf(this.fData[15])}));
                        break;
                    case (byte) 86:
                        this.fData[17] = (float) (((((((long) this.packBuffer[3]) << 24) & -16777216) | ((((long) this.packBuffer[2]) << 16) & 16711680)) | ((((long) this.packBuffer[1]) << 8) & 65280)) | (((long) this.packBuffer[0]) & 255));
                        this.fData[18] = ((float) (((((((long) this.packBuffer[7]) << 24) & -16777216) | ((((long) this.packBuffer[6]) << 16) & 16711680)) | ((((long) this.packBuffer[5]) << 8) & 65280)) | (((long) this.packBuffer[4]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[17])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[18])}));
                        break;
                    case (byte) 87:
                        long Longitude = ((((((long) this.packBuffer[3]) << 24) & -16777216) | ((((long) this.packBuffer[2]) << 16) & 16711680)) | ((((long) this.packBuffer[1]) << 8) & 65280)) | (((long) this.packBuffer[0]) & 255);
                        this.fData[19] = (float) (((double) (Longitude / 10000000)) + ((((double) ((float) (Longitude % 10000000))) / 100000.0d) / 60.0d));
                        long Latitude = ((((((long) this.packBuffer[7]) << 24) & -16777216) | ((((long) this.packBuffer[6]) << 16) & 16711680)) | ((((long) this.packBuffer[5]) << 8) & 65280)) | (((long) this.packBuffer[4]) & 255);
                        this.fData[20] = (float) (((double) (Latitude / 10000000)) + ((((double) ((float) (Latitude % 10000000))) / 100000.0d) / 60.0d));
                        //RecordData(sHead, String.format("% 14.6f", new Object[]{Float.valueOf(this.fData[19])}) + String.format("% 14.6f", new Object[]{Float.valueOf(this.fData[20])}));
                        break;
                    case (byte) 88:
                        this.fData[21] = ((float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255))) / 10.0f;
                        this.fData[22] = ((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 100.0f;
                        this.fData[23] = ((float) (((((((long) this.packBuffer[7]) << 24) & -16777216) | ((((long) this.packBuffer[6]) << 16) & 16711680)) | ((((long) this.packBuffer[5]) << 8) & 65280)) | (((long) this.packBuffer[4]) & 255))) / 1000.0f;
                        //RecordData(sHead, String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[21])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[22])}) + String.format("% 10.2f", new Object[]{Float.valueOf(this.fData[23])}));
                        break;
                    case (byte) 89:
                        this.fData[24] = ((float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255))) / 32768.0f;
                        this.fData[25] = ((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 32768.0f;
                        this.fData[26] = ((float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255))) / 32768.0f;
                        this.fData[27] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 32768.0f;
                        //RecordData(sHead, String.format("% 7.3f", new Object[]{Float.valueOf(this.fData[24])}) + String.format("% 7.3f", new Object[]{Float.valueOf(this.fData[25])}) + String.format("% 7.3f", new Object[]{Float.valueOf(this.fData[26])}) + String.format("% 7.3f", new Object[]{Float.valueOf(this.fData[27])}));
                        break;
                    case (byte) 90:
                        this.fData[28] = (float) ((((short) this.packBuffer[1]) << 8) | (((short) this.packBuffer[0]) & 255));
                        this.fData[29] = ((float) ((((short) this.packBuffer[3]) << 8) | (((short) this.packBuffer[2]) & 255))) / 100.0f;
                        this.fData[30] = ((float) ((((short) this.packBuffer[5]) << 8) | (((short) this.packBuffer[4]) & 255))) / 100.0f;
                        this.fData[31] = ((float) ((((short) this.packBuffer[7]) << 8) | (((short) this.packBuffer[6]) & 255))) / 100.0f;
                        //RecordData(sHead, String.format("% 5.0f", new Object[]{Float.valueOf(this.fData[28])}) + String.format("% 7.1f", new Object[]{Float.valueOf(this.fData[29])}) + String.format("% 7.1f", new Object[]{Float.valueOf(this.fData[30])}) + String.format("% 7.1f", new Object[]{Float.valueOf(this.fData[31])}));
                        break;
                    default:
                        break;
                }
            }
            this.iError++;
        }
        long lTimeNow = System.currentTimeMillis();
        if (lTimeNow - this.lLastTime > 80) {
            this.lLastTime = lTimeNow;
            Message msg = this.mHandler.obtainMessage(2);
            Bundle bundle = new Bundle();
            bundle.putFloatArray("Data", this.fData);
            bundle.putString("Date", this.strDate);
            bundle.putString("Time", this.strTime);
            msg.setData(bundle);
            if(!mSuspend)
                this.mHandler.sendMessage(msg);
        }
    }

    public void RecordData(byte ID, String str) {
        boolean Repeat = false;
        short sData = (short) (1 << (ID & 15));
        try {
            if ((this.IDNow & sData) != sData || sData >= this.sDataSave) {
                this.IDNow = (short) (this.IDNow | sData);
            } else {
                this.IDSave = this.IDNow;
                this.IDNow = sData;
                Repeat = true;
            }
            this.sDataSave = sData;
            switch (this.SaveState) {
                case 0:
                    this.myFile.Close();
                    this.SaveState = -1;
                    return;
                case 1:
                    this.myFile = new MyFile("/mnt/sdcard/Record.txt");
                    String s = "StartTime：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new Date(System.currentTimeMillis())) + "\r\n";
                    if ((this.IDSave & 2) > 0) {
                        s = s + "  AX： AY： AZ：";
                    }
                    if ((this.IDSave & 4) > 0) {
                        s = s + "  WX： WY： WZ：";
                    }
                    if ((this.IDSave & 8) > 0) {
                        s = s + "    AngleX：   AngleY：   AngleZ：";
                    }
                    if ((this.IDSave & 16) > 0) {
                        s = s + "   MagX：   MagY：   MagZ：";
                    }
                    if ((this.IDSave & 32) > 0) {
                        s = s + "Port0：Port1：Port2：Port3：";
                    }
                    if ((this.IDSave & 64) > 0) {
                        s = s + "    Pressure：    Height：";
                    }
                    if ((this.IDSave & 128) > 0) {
                        s = s + "        Longitude：        Latitude：";
                    }
                    if ((this.IDSave & 256) > 0) {
                        s = s + "    Elevation：    Coures：    Ground velocity：";
                    }
                    if ((this.IDSave & 512) > 0) {
                        s = s + "   q0：   q1：   q2：   q3：";
                    }
                    if ((this.IDSave & 1024) > 0) {
                        s = s + "Star Number：PDOP： HDOP： VDOP：";
                    }
                    this.myFile.Write(s + "\r\n");
                    if (Repeat) {
                        this.myFile.Write(str);
                        this.SaveState = 2;
                        return;
                    }
                    return;
                case 2:
                    if (Repeat) {
                        this.myFile.Write("  \r\n");
                    }
                    this.myFile.Write(str);
                    return;
                default:
                    return;
            }
        } catch (Exception e) {
        }
    }

    public void setRecord(boolean record) {
        if (record) {
            this.SaveState = 1;
        } else {
            this.SaveState = 0;
        }
    }
}
