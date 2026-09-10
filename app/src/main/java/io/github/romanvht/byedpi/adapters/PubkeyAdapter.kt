package io.github.romanvht.byedpi.adapters

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.core.PubkeyVault
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.utility.PubkeyUtils

class PubkeyAdapter(
    private val context: Context,
    private val onToggle: (Pubkey) -> Unit,
    private val onEdit: (Pubkey) -> Unit,
    private val onAction: (Pubkey, Int) -> Unit,
) : RecyclerView.Adapter<PubkeyAdapter.ViewHolder>() {

    private val items = mutableListOf<Pubkey>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val toggle: FrameLayout = view.findViewById(R.id.pubkey_toggle)
        val toggleCircle: ImageView = view.findViewById(R.id.pubkey_toggle_circle)
        val toggleIcon: ImageView = view.findViewById(R.id.pubkey_toggle_icon)
        val name: TextView = view.findViewById(R.id.pubkey_name)
        val description: TextView = view.findViewById(R.id.pubkey_description)
        val badge: ImageView = view.findViewById(R.id.pubkey_badge)
        val overflow: ImageButton = view.findViewById(R.id.pubkey_overflow)
    }

    fun update(newItems: List<Pubkey>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pubkey, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = items[position]
        val isLoaded = PubkeyVault.isLoaded(key.id)
        val imported = PubkeyUtils.SshKeyType.fromStoredType(key.type) == PubkeyUtils.SshKeyType.IMPORTED

        // Round indicator: green filled circle when the key is loaded,
        // gray outline when it is not (like ConnectBot's key list)
        holder.toggleCircle.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (isLoaded) R.drawable.bg_pubkey_toggle_filled else R.drawable.bg_pubkey_toggle_outline,
            ),
        )
        holder.toggleIcon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (isLoaded) R.drawable.ic_lock else R.drawable.ic_lock_open,
            ),
        )
        holder.toggleIcon.setColorFilter(
            if (isLoaded) Color.WHITE else ContextCompat.getColor(context, android.R.color.darker_gray),
        )

        holder.name.text = key.nickname
        holder.description.text = buildString {
            append(
                when (PubkeyUtils.SshKeyType.fromStoredType(key.type)) {
                    PubkeyUtils.SshKeyType.RSA -> "RSA"
                    PubkeyUtils.SshKeyType.DSA -> "DSA"
                    PubkeyUtils.SshKeyType.EC -> "ECDSA"
                    PubkeyUtils.SshKeyType.ED25519 -> "Ed25519"
                    else -> context.getString(R.string.pubkey_type_imported)
                }
            )
            PubkeyUtils.keyBits(key)?.let { append(" · ").append(it).append(" bit") }
            PubkeyUtils.fingerprint(key)?.let { append("\n").append(it) }
            if (key.isDefault) append("\n").append(context.getString(R.string.pubkey_default))
        }

        holder.badge.visibility = if (key.encrypted || key.keyPassword != null) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Row click toggles the loaded state, like in ConnectBot; the round
        // icon has its own listener so the tap target is explicit
        holder.toggle.isClickable = true
        holder.toggle.setOnClickListener { onToggle(key) }
        holder.itemView.setOnClickListener { onToggle(key) }

        holder.overflow.setOnClickListener { button ->
            val popup = PopupMenu(context, button)
            popup.menuInflater.inflate(R.menu.menu_pubkey_item, popup.menu)

            // Imported keys: no public key and no internal re-encryption
            if (imported) {
                popup.menu.findItem(R.id.action_copy_public).isEnabled = false
                popup.menu.findItem(R.id.action_export_public).isEnabled = false
                popup.menu.findItem(R.id.action_copy_pem).isVisible = false
                popup.menu.findItem(R.id.action_copy_encrypted).isVisible = false
                popup.menu.findItem(R.id.action_export_pem).isVisible = false
                popup.menu.findItem(R.id.action_export_encrypted).isVisible = false
            }

            showMenuIcons(popup)

            popup.setOnMenuItemClickListener { item ->
                onAction(key, item.itemId)
                true
            }
            popup.show()
        }
    }

    private fun showMenuIcons(popup: PopupMenu) {
        try {
            val menu = popup.menu
            val method = menu.javaClass.getMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(menu, true)
        } catch (e: Exception) {
            android.util.Log.w("PubkeyAdapter", "Cannot show menu icons: ${e.message}")
        }
    }
}
