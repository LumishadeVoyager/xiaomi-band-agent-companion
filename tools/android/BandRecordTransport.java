import android.bluetooth.*;
import android.net.*;
import java.io.*;
import java.util.UUID;
// ADB development transport only. The production Android service is not wired
// to this path yet. Device credentials remain on the developer's computer.
public class BandRecordTransport {
 static void pump(InputStream in, OutputStream out) throws Exception { byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);out.flush();} }
 public static void main(String[] args) throws Exception {
  android.os.Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");
  Object thread=at.getMethod("systemMain").invoke(null);
  android.content.Context system=(android.content.Context)at.getMethod("getSystemContext").invoke(thread);
  android.content.Context shell=system.createPackageContext("com.android.shell",0);
  Class<?> bm=Class.forName("android.os.BluetoothServiceManager");
  Class<?> bi=Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
  if(bi.getMethod("getBluetoothServiceManager").invoke(null)==null) bi.getMethod("setBluetoothServiceManager",bm).invoke(null,bm.getConstructor().newInstance());
  BluetoothManager manager=(BluetoothManager)shell.getSystemService(android.content.Context.BLUETOOTH_SERVICE);
  BluetoothAdapter adapter=manager.getAdapter();
  if(adapter==null) adapter=(BluetoothAdapter)BluetoothAdapter.class.getMethod("createAdapter",android.content.AttributionSource.class).invoke(null,new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build());
  if(args.length==0){System.out.println("Bluetooth enabled="+adapter.isEnabled()+" bonded="+adapter.getBondedDevices().size());return;}
  BluetoothDevice device=adapter.getRemoteDevice(args[0]);
  BluetoothSocket bt=device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
  bt.connect(); System.err.println("SPP connected");
  LocalServerSocket server=new LocalServerSocket("band_record_probe");
  LocalSocket client=server.accept();
  Thread tx=new Thread(()->{try {pump(client.getInputStream(),bt.getOutputStream());}catch(Exception e){System.err.println(e.getClass().getSimpleName());}finally{try{bt.close();}catch(Exception e){}}});
  tx.setDaemon(true);tx.start();
  try {pump(bt.getInputStream(),client.getOutputStream());}finally{client.close();server.close();bt.close();}
 }
}
