package com.example.nasboard.ui.setup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.nasboard.ime.activity.MainActivity
import com.example.nasboard.R
import com.example.nasboard.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var viewPager: ViewPager2
    private val viewModel: SetupViewModel by viewModels()

    companion object {
        private const val PREFS_NAME = "nasboard_setup"
        private const val KEY_SETUP_COMPLETED = "setup_completed"

        fun shouldSetup(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val setupCompleted = prefs.getBoolean(KEY_SETUP_COMPLETED, false)

            // 如果已经完成过设置，直接返回false
            if (setupCompleted) {
                return false
            }

            // 检查是否有未完成的设置步骤
            return SetupPage.hasUndonePage(context)
        }

        private fun setSetupCompleted(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    private fun setupViews() {
        val prevButton = binding.prevButton.apply {
            text = getString(R.string.setup_prev)
            setOnClickListener {
                if (viewPager.currentItem > 0) {
                    viewPager.currentItem -= 1
                }
            }
        }

        binding.skipButton.apply {
            text = getString(R.string.setup_skip)
            setOnClickListener {
                AlertDialog.Builder(this@SetupActivity)
                    .setMessage(R.string.setup_skip_hint)
                    .setPositiveButton(R.string.setup_skip_yes) { _, _ ->
                        // 跳过时不标记为已完成，下次还会显示
                        navigateToMain()
                    }
                    .setNegativeButton(R.string.setup_skip_no, null)
                    .show()
            }
        }

        val nextButton = binding.nextButton.apply {
            setOnClickListener {
                if (!viewPager.currentItem.isLastPage()) {
                    viewPager.currentItem += 1
                } else {
                    // 只有在完成所有步骤时才标记为已完成
                    if (isAllStepsCompleted()) {
                        setSetupCompleted(this@SetupActivity)
                    }
                    navigateToMain()
                }
            }
        }

        viewPager = binding.viewpager
        viewPager.adapter = Adapter(this)
        viewPager.isUserInputEnabled = false

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.isAllDone.value = viewModel.isAllDone.value

                prevButton.visibility = if (position != 0) View.VISIBLE else View.GONE

                nextButton.text = getString(
                    if (position.isLastPage()) {
                        R.string.setup_done
                    } else {
                        R.string.setup_next
                    }
                )
            }
        })

        viewModel.isAllDone.observe(this) { allDone ->
            nextButton.apply {
                visibility = if (allDone || !viewPager.currentItem.isLastPage()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }

        SetupPage.firstUndonePage(this)?.let { page ->
            viewPager.currentItem = page.ordinal
        }
    }

    private fun isAllStepsCompleted(): Boolean {
        // 检查所有步骤是否都已完成
        return SetupPage.entries.all { it.isDone(this) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val fragmentTag = "f" + viewPager.currentItem
            val fragment = supportFragmentManager.findFragmentByTag(fragmentTag)
            (fragment as? SetupFragment)?.sync()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private inner class Adapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment {
            return SetupFragment().apply {
                arguments = Bundle().apply {
                    putInt("page", position)
                }
            }
        }
    }
}