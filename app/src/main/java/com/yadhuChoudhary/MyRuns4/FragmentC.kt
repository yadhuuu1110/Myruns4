package com.yadhuChoudhary.MyRuns4
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.core.net.toUri
import com.yadhuChoudhary.MyRuns4.ProfileActivity
class FragmentC : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val accountPref: Preference? = findPreference("account_pref")
        accountPref?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), ProfileActivity::class.java)
            startActivity(intent)
            true
        }

        val webpagePref: Preference? = findPreference("webpage")
        webpagePref?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://www.sfu.ca/computing.html".toUri())
            startActivity(intent)
            true
        }
    }
}
