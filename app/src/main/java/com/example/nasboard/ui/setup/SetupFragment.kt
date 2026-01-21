package com.example.nasboard.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.nasboard.databinding.FragmentSetupBinding

class SetupFragment : Fragment() {

    private val viewModel: SetupViewModel by activityViewModels()
    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var page: SetupPage

    private var isDone: Boolean = false
        set(new) {
            if (new && page.isLastPage()) {
                viewModel.isAllDone.value = true
            }
            with(binding) {
                stepText.text = page.getStepText(requireContext())
                hintText.text = page.getHintText(requireContext())
                actionButton.visibility = if (new) View.GONE else View.VISIBLE
                actionButton.text = page.getButtonText(requireContext())
                actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
                doneText.visibility = if (new) View.VISIBLE else View.GONE
            }
            field = new
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)

        arguments?.getInt("page")?.let { position ->
            page = SetupPage.entries[position]
        }

        sync()
        return binding.root
    }

    fun sync() {
        isDone = page.isDone(requireContext())
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}