package com.example.myapplication3

import android.view.*
import android.widget.*

class AudioFileAdapter(
    private val files: List<AudioFileInfo>,
    private val activity: MainActivity
) : BaseAdapter() {

    override fun getCount(): Int = files.size
    override fun getItem(position: Int): Any = files[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: ViewHolder

        if (convertView == null) {
            view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_audio, parent, false)
            holder = ViewHolder(
                tvFileNameLength = view.findViewById(R.id.tvFileNameLength),
                btnMenu = view.findViewById(R.id.btnMenu),
                btnPlay = view.findViewById(R.id.btnPlay),
                seekBarAudio = view.findViewById(R.id.seekBarAudio),
                tvPlaybackTime = view.findViewById(R.id.tvPlaybackTime) // Added tvPlaybackTime
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        val item = files[position]
        holder.tvFileNameLength.text = "${item.displayName} - ${activity.formatElapsedTime(item.durationMs)}"

        holder.btnMenu.setOnClickListener { btnView ->
            showPopupMenu(btnView, item)
        }

        val isCurrentlyPlayingThisItem = activity.currentlyPlayingPath == item.file.absolutePath
        val isPlaying = isCurrentlyPlayingThisItem && !activity.isMediaPlayerPaused

        if (isCurrentlyPlayingThisItem) {
            if (activity.isMediaPlayerPaused) {
                holder.btnPlay.setImageResource(R.drawable.ic_play_arrow) // Paused state
            } else {
                holder.btnPlay.setImageResource(R.drawable.ic_pause) // Playing state
            }
            holder.seekBarAudio.visibility = View.VISIBLE
            holder.tvPlaybackTime.visibility = View.VISIBLE // Show playback time
            holder.seekBarAudio.max = item.durationMs.toInt()
            holder.seekBarAudio.progress = activity.getCurrentMediaPlayerPosition()
            // Update playback time text here initially as well
            val currentPosition = activity.getCurrentMediaPlayerPosition()
            val totalDuration = item.durationMs
            holder.tvPlaybackTime.text = "${activity.formatElapsedTime(currentPosition.toLong())} / ${activity.formatElapsedTime(totalDuration)}"
        } else {
            holder.btnPlay.setImageResource(R.drawable.ic_play_arrow) // Default: play
            holder.seekBarAudio.visibility = View.GONE
            holder.tvPlaybackTime.visibility = View.GONE // Hide playback time
        }

        holder.btnPlay.setOnClickListener {
            activity.playAudio(item, holder)
        }

        holder.seekBarAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isCurrentlyPlayingThisItem) {
                    // Update time label when user scrubs the seekbar
                    val totalDuration = item.durationMs
                    holder.tvPlaybackTime.text = "${activity.formatElapsedTime(progress.toLong())} / ${activity.formatElapsedTime(totalDuration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed for now
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null && isCurrentlyPlayingThisItem) {
                    activity.seekMediaPlayerTo(seekBar.progress)
                }
            }
        })

        return view
    }

    private fun showPopupMenu(anchor: View, audioFile: AudioFileInfo) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_audio_item, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_rename -> {
                    activity.showRenameDialog(audioFile)
                    true
                }
                R.id.menu_move -> {
                    if (files === activity.permanentAudioFiles) {
                        Toast.makeText(activity, "Already in permanent storage", Toast.LENGTH_SHORT).show()
                        true
                    } else {
                        activity.showMoveToPermanentDialog(audioFile)
                        true
                    }
                }
                R.id.menu_delete -> {
                    activity.deleteFile(audioFile)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    data class ViewHolder(
        val tvFileNameLength: TextView,
        val btnMenu: ImageButton,
        val btnPlay: ImageButton,
        val seekBarAudio: SeekBar,
        val tvPlaybackTime: TextView // Added tvPlaybackTime
    )
}
