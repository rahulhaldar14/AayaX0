package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AIProviderRepository
import com.jarvis.assistant.ai.AIProviderType
import com.jarvis.assistant.ai.UnifiedAIClient
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private var providerRequestJob: Job? = null

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter
        updateProviderLabel()
        binding.tvChatSubtitle.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
        }

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            if (providerRequestJob?.isActive == true) providerRequestJob?.cancel() else sendMessage()
        }

        // New Chat / Clear
        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Back / Menu
        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        // Mode Chips
        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Persona: AYHA Active", Toast.LENGTH_SHORT).show()
        }

        binding.chipMode.setOnClickListener {
            Toast.makeText(requireContext(), "Mode: Code & Assistant", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttach.setOnClickListener {
            Toast.makeText(requireContext(), "Attachment options", Toast.LENGTH_SHORT).show()
        }

        observeChatTurns()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim() ?: ""
        if (text.isBlank()) return

        val providers = AIProviderRepository(requireContext())
        if (providers.activeType() != AIProviderType.GEMINI_LIVE) {
            binding.etMessageInput.text?.clear()
            binding.etMessageInput.isEnabled = false
            providerRequestJob = viewLifecycleOwner.lifecycleScope.launch {
                val repository = JarvisApp.instance.chatRepository
                val turnId = repository.beginStreamingTurn(text)
                binding.btnSendMessage.setImageResource(com.jarvis.assistant.R.drawable.ic_stop)
                binding.btnSendMessage.contentDescription = "Stop generation"
                binding.tvChatSubtitle.text = "Thinking… tap Stop to cancel"
                try {
                    val response = UnifiedAIClient(providers).chat(text) { partial ->
                        repository.updateStreamingTurn(turnId, partial)
                    }
                    repository.updateStreamingTurn(turnId, response)
                } catch (cancelled: CancellationException) {
                    val partial = repository.turns.value.firstOrNull { it.id == turnId }?.jarvisResponse.orEmpty()
                    repository.updateStreamingTurn(turnId, if (partial == "…") "Generation stopped." else "$partial\n\n[Generation stopped]")
                    if (_binding != null) {
                        binding.etMessageInput.setText(text)
                        Toast.makeText(requireContext(), "Generation stopped", Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    val partial = repository.turns.value.firstOrNull { it.id == turnId }?.jarvisResponse.orEmpty()
                    repository.updateStreamingTurn(
                        turnId,
                        if (partial == "…") "Request failed: ${error.message ?: "Unknown provider error"}"
                        else "$partial\n\n[Request failed: ${error.message ?: "Unknown provider error"}]"
                    )
                    if (_binding != null) {
                        binding.etMessageInput.setText(text)
                        Toast.makeText(requireContext(), error.message ?: "Request failed. Check the provider and model, then retry.", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    providerRequestJob = null
                    if (_binding != null) {
                        binding.etMessageInput.isEnabled = true
                        binding.btnSendMessage.setImageResource(com.jarvis.assistant.R.drawable.ic_send_up)
                        binding.btnSendMessage.contentDescription = "Send"
                        updateProviderLabel()
                    }
                }
            }
            return
        }

        if (!JarvisConversationService.isServiceRunning()) {
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }

        JarvisConversationService.sendTextMessage(text)
        binding.etMessageInput.text?.clear()
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            updateProviderLabel()
        }
    }

    private fun updateProviderLabel() {
        if (_binding == null) return
        val repository = AIProviderRepository(requireContext())
        val type = repository.activeType()
        binding.tvChatTitle.text = "AYHA AI"
        binding.tvChatSubtitle.text = if (type == AIProviderType.GEMINI_LIVE) {
            "Gemini Live • ${JarvisApp.instance.preferences.aiModel.removePrefix("models/")} • Voice"
        } else {
            val config = repository.get(type)
            "${type.displayName} • ${config?.modelId ?: "Not configured"}"
        }
        binding.tvChatSubtitle.contentDescription = "Active provider and model. Tap to configure."
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
