package io.github.romanvht.byedpi.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.SshHost

class SshHostAdapter(
    private val context: Context,
    private val onEdit: (SshHost) -> Unit,
    private val onSelect: (SshHost) -> Unit,
    private val onDelete: (SshHost) -> Unit,
) : RecyclerView.Adapter<SshHostAdapter.ViewHolder>() {

    private val items = mutableListOf<SshHost>()
    private var activeId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radioButton: RadioButton = view.findViewById(R.id.host_radio)
        val name: TextView = view.findViewById(R.id.host_name)
        val details: TextView = view.findViewById(R.id.host_details)
        val auth: TextView = view.findViewById(R.id.host_auth)
        val delete: ImageButton = view.findViewById(R.id.host_delete)
    }

    fun update(newItems: List<SshHost>, newActiveId: String?) {
        items.clear()
        items.addAll(newItems)
        activeId = newActiveId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ssh_host, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val host = items[position]

        holder.radioButton.isChecked = host.id == activeId
        holder.radioButton.setOnClickListener { onSelect(host) }

        holder.name.text = host.name.ifBlank { host.hostname }
        holder.details.text = "${host.username}@${host.hostname}:${host.port}"
        holder.auth.text = when (host.authType) {
            SshHost.AuthType.PASSWORD -> context.getString(R.string.ssh_auth_password)
            SshHost.AuthType.KEY -> context.getString(R.string.ssh_auth_key)
            SshHost.AuthType.ANY -> context.getString(R.string.ssh_auth_any)
        }

        holder.itemView.setOnClickListener { onEdit(host) }
        holder.delete.setOnClickListener { onDelete(host) }
    }
}
