package com.pitchgauge.j9pr.pitchgauge;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/* compiled from: BluetoothService */
class MyFile {
    FileOutputStream fout;

    public MyFile(String fileName) throws FileNotFoundException {
        this.fout = new FileOutputStream(fileName, false);
    }

    public void Write(String str) throws IOException {
        this.fout.write(str.getBytes());
    }

    public void Close() throws IOException {
        this.fout.close();
        this.fout.flush();
    }
}
