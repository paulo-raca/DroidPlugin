package com.example.TestPlugin;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.morgoo.droidplugin.pm.PluginManager;
import com.morgoo.helper.compat.PackageManagerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.morgoo.helper.compat.PackageManagerCompat.*;

public class ApkFragment extends ListFragment implements ServiceConnection {
    private ArrayAdapter<ApkItem> adapter;
    final Handler handler = new Handler();

    public ApkFragment() {
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        adapter = new ArrayAdapter<ApkItem>(getActivity(), 0) {
            @Override
            public View getView(final int position, View convertView, final ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getActivity()).inflate(R.layout.apk_item, null);
                }
                ApkItem item = getItem(position);

                ImageView icon = (ImageView) convertView.findViewById(R.id.imageView);
                icon.setImageDrawable(item.icon);

                TextView title = (TextView) convertView.findViewById(R.id.textView1);
                title.setText(item.title);

                TextView version = (TextView) convertView.findViewById(R.id.textView2);
                version.setText(String.format("%s(%s)", item.versionName, item.versionCode));

                TextView btn3 = (TextView) convertView.findViewById(R.id.button3);
                btn3.setText("delete");
                btn3.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        onListItemClick(getListView(), view, position, getItemId(position));
                    }
                });
                TextView btn = (TextView) convertView.findViewById(R.id.button2);
                try {
                    if (item.installing) {
                        btn.setText("Installation ing");
                    } else {
                        if (PluginManager.getInstance().isConnected()) {
                            btn.setText(PluginManager.getInstance().getPackageInfo(item.packageInfo.packageName, 0) != null ? "Installed" : "installation");
                        } else {
                            btn.setText("Wait Initialization service");
                        }
                    }
                } catch (Exception e) {
                    btn.setText("Installation 1");
                }
                btn.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        onListItemClick(getListView(), view, position, getItemId(position));
                    }
                });


                return convertView;
            }
        };

    }

    private void doUninstall(final ApkItem item) {
        AlertDialog.Builder builder = new Builder(getActivity());
        builder.setTitle("Warning, you sure you want to delete it?");
        builder.setMessage("Warning Are you sure you want to delete" + item.title + "What?");
        builder.setNegativeButton("delete", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                new File(item.apkfile).delete();
                adapter.remove(item);
                Toast.makeText(getActivity(), "successfully deleted", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNeutralButton("cancel", null);
        builder.show();
    }

    boolean isViewCreated = false;

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isViewCreated = true;
        setEmptyText("I did not find the apk in sdcard");
        setListAdapter(adapter);
        setListShown(false);
        getListView().setOnItemClickListener(null);
        if (PluginManager.getInstance().isConnected()) {
            startLoad();
        } else {
            PluginManager.getInstance().addServiceConnection(this);
        }
    }

    @Override
    public void onDestroyView() {
        isViewCreated = false;
        super.onDestroyView();
    }

    @Override
    public void setListShown(boolean shown) {
        if (isViewCreated) {
            super.setListShown(shown);
        }
    }

    private void startLoad() {
        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            startLoadInner();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0x1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0x1) {
            if (permissions != null && permissions.length > 0) {
                for (int i = 0; i < permissions.length; i++) {
                    String permisson = permissions[i];
                    int grantResult = grantResults[i];
                    if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(permisson)) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            startLoadInner();
                        } else {
                            Toast.makeText(getActivity(), "There is no authorization can not be used.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                for (String permisson : permissions) {

                }
            }
        }
    }

    private void startLoadInner() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                setListShown(true);
            }
        });
        if (!isViewCreated) {
            return;
        }
        new Thread("ApkScanner") {
            @Override
            public void run() {
                File file = Environment.getExternalStorageDirectory();

                List<File> apks = new ArrayList<File>(10);
                File[] files = file.listFiles();
                if (files != null) {
                    for (File apk : files) {
                        if (apk.exists() && apk.getPath().toLowerCase().endsWith(".apk")) {
                            apks.add(apk);
                        }
                    }
                }


                file = new File(Environment.getExternalStorageDirectory(), "360Download");
                if (file.exists() && file.isDirectory()) {
                    File[] files1 = file.listFiles();
                    if (files1 != null) {
                        for (File apk : files1) {
                            if (apk.exists() && apk.getPath().toLowerCase().endsWith(".apk")) {
                                apks.add(apk);
                            }
                        }
                    }

                }
                PackageManager pm = getActivity().getPackageManager();
                for (final File apk : apks) {
                    try {
                        if (apk.exists() && apk.getPath().toLowerCase().endsWith(".apk")) {
                            final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
                            if (info != null && isViewCreated) {
                                try {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            adapter.add(new ApkItem(getActivity(), info, apk.getPath()));
                                        }
                                    });
                                } catch (Exception e) {
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }.start();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final ApkItem item = adapter.getItem(position);
        if (v.getId() == R.id.button2) {
            if (item.installing) {
                return;
            }
            if (!PluginManager.getInstance().isConnected()) {
                Toast.makeText(getActivity(), "Widget Service is initializing, please try again later ...", Toast.LENGTH_SHORT).show();
            }
            try {
                if (PluginManager.getInstance().getPackageInfo(item.packageInfo.packageName, 0) != null) {
                    Toast.makeText(getActivity(), "Has been installed, you can not install", Toast.LENGTH_SHORT).show();
                } else {
                    new Thread() {
                        @Override
                        public void run() {
                            doInstall(item);
                        }
                    }.start();

                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    PluginManager.getInstance().installPackage(item.apkfile, 0);
                } catch (RemoteException e1) {
                    e1.printStackTrace();
                }
                adapter.remove(item);
            }
        } else if (v.getId() == R.id.button3) {
            doUninstall(item);
        }
    }

    private synchronized void doInstall(ApkItem item) {
        item.installing = true;

        handler.post(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
        try {
            final int re = PluginManager.getInstance().installPackage(item.apkfile, 0);
            item.installing = false;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    switch (re) {
                        case PluginManager.INSTALL_FAILED_NO_REQUESTEDPERMISSION:
                            Toast.makeText(getActivity(), "Installation failed, too many permissions to the requested file.", Toast.LENGTH_SHORT).show();
                            break;
                        case INSTALL_FAILED_NOT_SUPPORT_ABI:
                            Toast.makeText(getActivity(), "Host does not support plug-abi environment, the host may be running a 64-bit, but only supports 32-bit plug-ins.", Toast.LENGTH_SHORT).show();
                            break;
                        case INSTALL_SUCCEEDED:
                            Toast.makeText(getActivity(), "The installation is complete", Toast.LENGTH_SHORT).show();
                            adapter.notifyDataSetChanged();
                            break;
                    }

                }
            });
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        startLoad();
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
    }

    @Override
    public void onDestroy() {
        PluginManager.getInstance().removeServiceConnection(this);
        super.onDestroy();
    }
}
