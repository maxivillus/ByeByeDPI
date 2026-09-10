package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.SshHostAdapter
import io.github.romanvht.byedpi.core.SshTunnelManager
import io.github.romanvht.byedpi.data.SshHost
import io.github.romanvht.byedpi.utility.SshHostUtils

class SshHostsActivity : BaseActivity() {

    private lateinit var adapter: SshHostAdapter
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var emptyView: TextView
    private lateinit var hintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_hosts)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        enabledSwitch = findViewById(R.id.ssh_enabled_switch)
        emptyView = findViewById(R.id.ssh_empty)
        hintView = findViewById(R.id.ssh_hint)

        adapter = SshHostAdapter(
            context = this,
            onEdit = { host ->
                val intent = Intent(this, SshHostEditorActivity::class.java)
                intent.putExtra(SshHostEditorActivity.EXTRA_HOST_ID, host.id)
                startActivity(intent)
            },
            onSelect = { host ->
                SshHostUtils.setActiveHostId(this, host.id)
                refresh()
            },
            onDelete = { host -> confirmDelete(host) },
        )

        val recyclerView = findViewById<RecyclerView>(R.id.ssh_hosts_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.ssh_add).setOnClickListener {
            val intent = Intent(this, SshHostEditorActivity::class.java)
            startActivity(intent)
        }

        enabledSwitch.isChecked = SshHostUtils.isSshEnabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            SshHostUtils.setSshEnabled(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_ssh_hosts, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_logs -> {
            startActivity(Intent(this, LogsActivity::class.java))
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val hosts = SshHostUtils.getHosts(this)
        val activeId = SshHostUtils.getActiveHost(this)?.id

        adapter.update(hosts, activeId)

        val isEmpty = hosts.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        hintView.visibility = if (isEmpty) View.GONE else View.VISIBLE

        val lastError = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("ssh_last_error", null)

        hintView.text = buildString {
            append(
                if (!lastError.isNullOrBlank()) {
                    getString(R.string.ssh_last_error, lastError)
                } else {
                    getString(R.string.ssh_list_hint)
                }
            )
            append("\n")
            append(getString(R.string.tunnel_state, describeState(SshTunnelManager.state.value)))
        }
    }

    private fun describeState(state: SshTunnelManager.State): String = when (state) {
        SshTunnelManager.State.Idle -> getString(R.string.tunnel_state_idle)
        is SshTunnelManager.State.Connecting ->
            getString(R.string.tunnel_state_connecting, state.hostname)
        is SshTunnelManager.State.Connected ->
            getString(R.string.tunnel_state_connected, state.localPort)
        is SshTunnelManager.State.Reconnecting ->
            getString(R.string.tunnel_state_reconnecting, state.attempt, state.error ?: "")
        is SshTunnelManager.State.Failed ->
            getString(R.string.tunnel_state_failed, state.error ?: "")
    }

    private fun confirmDelete(host: SshHost) {
        val name = host.name.ifBlank { host.hostname }
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_delete_host)
            .setMessage(getString(R.string.ssh_delete_confirm, name))
            .setPositiveButton(R.string.ssh_delete) { _, _ ->
                SshHostUtils.deleteHost(this, host.id)
                refresh()
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }
}
