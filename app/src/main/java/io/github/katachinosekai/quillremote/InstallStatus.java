package io.github.katachinosekai.quillremote;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.widget.Toast;

public class InstallStatus extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    static Intent confirmIntent(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = confirmIntent(intent);
            if (confirm == null) {
                return;
            }
            ComponentName target = confirm.resolveActivity(c.getPackageManager());
            boolean system = false;
            try {
                system = target != null && (c.getPackageManager().getApplicationInfo(target.getPackageName(), 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (!system) {
                Toast.makeText(c, "Update not installed: unexpected installer", Toast.LENGTH_LONG).show();
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Update not installed" + (m != null ? ": " + m : ""), Toast.LENGTH_LONG).show();
        }
    }
}
