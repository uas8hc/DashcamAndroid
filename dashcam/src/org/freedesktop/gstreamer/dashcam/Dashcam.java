package org.freedesktop.gstreamer.dashcam;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.Formatter;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;

import org.freedesktop.gstreamer.GStreamer;

public class Dashcam extends Activity implements SurfaceHolder.Callback {

    private native void nativeInit(String ip);     // Initialize native code, build pipeline, etc

    private native void nativeFinalize(); // Destroy pipeline and shutdown native code

    private native void nativePlay();     // Set pipeline to PLAYING

    private native void nativePause();    // Set pipeline to PAUSED

    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks

    private native void nativeSurfaceInit(Object surface);

    private native void nativeSurfaceFinalize();

    private long native_custom_data;      // Native code will use this to keep private data

    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING

    private PrintWriter m_outputWriter;
    private BufferedReader m_inputReader;
    private final String SERVER_IP = "192.168.0.104";
    private final int SERVER_PORT = 4444;
    EditText editFieldIp, editFieldPort, editMessage;
    TextView textviewSocket;
    Button btnSend, btnConnect, btnDisconnect;
    Thread connectThread = null;
    Thread writeThread = null;
    Socket socket;
//    private Socket client;
//    private PrintWriter printwriter;

    // Called when the activity is first created.
    @SuppressLint("SetTextI18n")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize GStreamer and warn if it fails
        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.main);
        class MyApllication extends Application{
            public static final String CHANNEL_ID = "push_notification_id";
            @Override
            public void onCreate() {
                super.onCreate();
                createChannelNotification();
            }

            private void createChannelNotification() {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PushNotification", NotificationManager.IMPORTANCE_DEFAULT);
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    manager.createNotificationChannel(channel);
                }
            }
        }

        final String TAG = MyFirebaseMessagingService.class.getName();
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                            return;
                        }

                        // Get new FCM registration token
                        String token = task.getResult();

                        // Log and toast
                        Log.e(TAG, token);
                    }
                });
        class MyFirebaseMessagingService extends FirebaseMessagingService {
            public static final String TAG = Dashcam.MyFirebaseMessagingService.class.getName();
            @Override
            public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
                super.onMessageReceived(remoteMessage);
//        RemoteMessage.Notification notification = message.getNotification();
//        if (notification == null){
//            return;
//        }
//        String strTitle = notification.getTitle();
//        String strMessage = notification.getBody();


                //Data Messages
                Map<String, String> stringMap = remoteMessage.getData();
                String title = stringMap.get("user-name");
                String body = stringMap.get("description");
                sendNotification(title, body);
            }
            private void sendNotification(String strTitle, String strMessage) {
                Intent intent = new Intent(this, Dashcam.MyApllication.class);
                @SuppressLint("UnspecifiedImmutableFlag")
                PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent, PendingIntent.FLAG_UPDATE_CURRENT);
                NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, Dashcam.MyApllication.CHANNEL_ID)
                        .setContentTitle(strTitle)
                        .setContentText(strMessage)
                        .setSmallIcon(R.drawable.gstreamer_logo_3)
                        .setContentIntent(pendingIntent);
                Notification notification = notificationBuilder.build();
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null){
                    notificationManager.notify(1,notification);
                }
            }

            @Override
            public void onNewToken(@NonNull String token) {
                super.onNewToken(token);
                Log.d(TAG, "Refreshed token: " + token);

                // If you want to send messages to this application instance or
                // manage this apps subscriptions on the server side, send the
                // FCM registration token to your app server.
            }
        };
        editFieldIp = (EditText) findViewById(R.id.editFieldIp);
        editFieldPort = (EditText) findViewById(R.id.eidtFieldPort);
        textviewSocket = (TextView) findViewById(R.id.textViewSocket);
        editMessage = (EditText) findViewById(R.id.editMessage);
        btnSend = (Button) findViewById(R.id.btnSend);
        btnConnect = (Button) findViewById(R.id.btnConnect);
        btnDisconnect = (Button) findViewById(R.id.btnDisConnect);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                textviewSocket.setText("");
                connectThread = new Thread(new SocketConnectThread());
                connectThread.start();
            }
        });

        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (socket != null) {
                        m_outputWriter.write("Disconnect");
                        socket.close();
                        socket = null;
                    }
                    if (m_inputReader != null) {
                        m_inputReader.close();
                        m_inputReader = null;
                    }
                    if (m_outputWriter != null) {
                        m_outputWriter.close();
                        m_outputWriter = null;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textviewSocket.setText("Disconnected");
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String message = "Enable Stream";
                if (!message.isEmpty()) {
                    writeThread = new Thread(new SocketWriteThread(message));
                    writeThread.start();
                }
            }
        });

//        btnSend.setOnClickListener(new View.OnClickListener() {
//			public void onClick(View v) {
//				// get the text message on the text field
//				String message = editMessage.getText().toString();
//				// start the Thread to connect to server
//				new Thread(new ClientThread(message)).start();
//
//			}
//		});


        // display public ip address
        TextView textViewIp = (TextView) this.findViewById(R.id.textView_ip);
        String displayIpString = "IP: [" + this.getDeviceIP() + "] | Mac: [" + this.getDeviceMac() + "]";
        textViewIp.setText(displayIpString);

        ImageButton play = (ImageButton) this.findViewById(R.id.button_play);
        play.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                is_playing_desired = true;
                nativePlay();
            }
        });

        ImageButton pause = (ImageButton) this.findViewById(R.id.button_stop);
        pause.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                is_playing_desired = false;
                nativePause();
            }
        });

        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            Log.i("GStreamer", "Activity created. Saved state is playing:" + is_playing_desired);
        } else {
            is_playing_desired = false;
            Log.i("GStreamer", "Activity created. There is no saved state, playing: false");
        }

        // Start with disabled buttons, until native code is initialized
        this.findViewById(R.id.button_play).setEnabled(false);
        this.findViewById(R.id.button_stop).setEnabled(false);

        nativeInit(this.getDeviceIP());
    }

    protected void onSaveInstanceState(Bundle outState) {
        Log.d("GStreamer", "Saving state, playing:" + is_playing_desired);
        outState.putBoolean("playing", is_playing_desired);
    }

    protected void onDestroy() {
        nativeFinalize();
        super.onDestroy();
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        runOnUiThread(new Runnable() {
            public void run() {
                tv.setText(message);
            }
        });
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized() {
        Log.i("GStreamer", "Gst initialized. Restoring state, playing:" + is_playing_desired);
        // Restore previous playing state
        if (is_playing_desired) {
            nativePlay();
        } else {
            nativePause();
        }

        // Re-enable buttons, now that GStreamer is initialized
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            public void run() {
                activity.findViewById(R.id.button_play).setEnabled(true);
                activity.findViewById(R.id.button_stop).setEnabled(true);
            }
        });
    }

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("dashcam");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit(holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize();
    }

    public String getDeviceIP() {
        WifiManager wifi_manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        return Formatter.formatIpAddress(wifi_manager.getConnectionInfo().getIpAddress());
    }

    public String getDeviceMac() {
        WifiManager wifi_manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        return wifi_manager.getConnectionInfo().getMacAddress();
    }

    class SocketConnectThread implements Runnable {
        public void run() {
            try {
                if (socket == null) {
                    String server = editFieldIp.getText().toString();
                    String port = editFieldPort.getText().toString();

                    if ((Objects.equals(server, "")) || (Objects.equals(port, ""))) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String msg = "Connecting to " + SERVER_IP + ":" + SERVER_PORT;
                                textviewSocket.setText(msg);
                            }
                        });
                        socket = new Socket(SERVER_IP, SERVER_PORT); // connect to server
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String msg = "Connecting to " + server + ":" + port;
                                textviewSocket.setText(msg);
                            }
                        });
                        socket = new Socket(server, Integer.valueOf(port));
                    }
                    m_outputWriter = new PrintWriter(socket.getOutputStream());
                    m_inputReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textviewSocket.setText("Connected");
                        }
                    });
                    new Thread(new SocketReadThread()).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (socket == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textviewSocket.setText("Connection Failed");
                    }
                });
            }
        }
    }

    class SocketReadThread implements Runnable {
        @Override
        public void run() {
            while (true) {
                if ((socket == null) || (m_inputReader == null) || ((m_outputWriter == null))) {
                    continue;
                }

                try {
                    final String message = m_inputReader.readLine();
                    if (message != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textviewSocket.append("server: " + message + " ");
                            }
                        });
                    } else {
                        connectThread = new Thread(new SocketConnectThread());
                        connectThread.start();
                        return;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    class MyApllication extends Application{
        public static final String CHANNEL_ID = "push_notification_id";
        @Override
        public void onCreate() {
            super.onCreate();
            createChannelNotification();
        }

        private void createChannelNotification() {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PushNotification", NotificationManager.IMPORTANCE_DEFAULT);
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.createNotificationChannel(channel);
            }
        }
    }

    class MyFirebaseMessagingService extends FirebaseMessagingService {
        public static final String TAG = MyFirebaseMessagingService.class.getName();
        @Override
        public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
            super.onMessageReceived(remoteMessage);
//        RemoteMessage.Notification notification = message.getNotification();
//        if (notification == null){
//            return;
//        }
//        String strTitle = notification.getTitle();
//        String strMessage = notification.getBody();


            //Data Messages
            Map<String, String> stringMap = remoteMessage.getData();
            String title = stringMap.get("user-name");
            String body = stringMap.get("description");
            sendNotification(title, body);
        }
        private void sendNotification(String strTitle, String strMessage) {
            Intent intent = new Intent(this, MyApllication.class);
            @SuppressLint("UnspecifiedImmutableFlag")
            PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, MyApllication.CHANNEL_ID)
                    .setContentTitle(strTitle)
                    .setContentText(strMessage)
                    .setSmallIcon(R.drawable.gstreamer_logo_3)
                    .setContentIntent(pendingIntent);
            Notification notification = notificationBuilder.build();
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null){
                notificationManager.notify(1,notification);
            }
        }

        @Override
        public void onNewToken(@NonNull String token) {
            super.onNewToken(token);
            Log.d(TAG, "Refreshed token: " + token);

            // If you want to send messages to this application instance or
            // manage this apps subscriptions on the server side, send the
            // FCM registration token to your app server.
        }
    }
    class MainActivity extends AppCompatActivity {

        public static final String TAG = MyFirebaseMessagingService.class.getName();
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);





            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(new OnCompleteListener<String>() {
                        @Override
                        public void onComplete(@NonNull Task<String> task) {
                            if (!task.isSuccessful()) {
                                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                                return;
                            }

                            // Get new FCM registration token
                            String token = task.getResult();

                            // Log and toast
                            Log.e(TAG, token);
                        }
                    });

        }




    }

    class SocketWriteThread implements Runnable {
        private String message;

        SocketWriteThread(String message) {
            this.message = message;
        }

        @Override
        public void run() {
            if ((socket == null) || (m_inputReader == null) || ((m_outputWriter == null))) {
                Log.d("Dashcam", "Socket is null in SocketWriteThread");
                return;
            }

            String msg = editMessage.getText().toString();
            m_outputWriter.write(msg);
            m_outputWriter.flush();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String msg = editMessage.getText().toString();
                    textviewSocket.setText("Sent: " + msg);
                }
            });
        }
    }



//    class ClientThread implements Runnable {
//		private final String message;
//
//		ClientThread(String message) {
//			this.message = message;
//		}
//		@Override
//		public void run() {
//			try {
//				// the IP and port should be correct to have a connection established
//				// Creates a stream socket and connects it to the specified port number on the named host.
//                String server = editFieldIp.getText().toString();
//                String port = editFieldPort.getText().toString();
//
//                if ((Objects.equals(server, "")) || (Objects.equals(port, ""))){
//                    client = new Socket(SERVER_IP, SERVER_PORT); // connect to server
//                }
//                else {
//                    client = new Socket(server, Integer.valueOf(port));
//                }
//				printwriter = new PrintWriter(client.getOutputStream(),true);
//				printwriter.write(message); // write the message to output stream
//
//				printwriter.flush();
//				printwriter.close();
//
//				// closing the connection
//				client.close();
//
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//
//			// updating the UI
//			runOnUiThread(new Runnable() {
//				@Override
//				public void run() {
//                    String msg = "sent: " + message;
//					textviewSocket.setText(msg);
//				}
//			});
//		}
//	}
}
