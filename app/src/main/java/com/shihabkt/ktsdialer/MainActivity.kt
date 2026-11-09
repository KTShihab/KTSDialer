package com.shihabkt.ktsdialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.shihabkt.ktsdialer.databinding.ActivityMainBinding

/**
 * Main Activity hosting:
 * - Top App Bar (Toolbar) with options menu for Settings
 * - Bottom Navigation for Dial, Contact, and Favorite tabs
 * - Unified permission requests for contacts and calls
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val appPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.MANAGE_OWN_CALLS
    )

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true) {
            Toast.makeText(
                this,
                getString(R.string.default_dialer_success),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readContactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true
        if (!readContactsGranted) {
            Toast.makeText(
                this,
                getString(R.string.permission_contacts_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomNavigation()

        CallRecorder.refreshRecordingsCacheAsync(this)
        checkAndRequestContactsPermissions()

        if (savedInstanceState == null) {
            replaceFragment(DialFragment.newInstance(), TAG_DIAL)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.mainToolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_top_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAndRequestContactsPermissions() {
        val allGranted = appPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            contactsPermissionLauncher.launch(appPermissions)
        }
    }

    private fun checkAndRequestDefaultDialerRole() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                defaultDialerLauncher.launch(intent)
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_dial -> {
                    replaceFragment(DialFragment.newInstance(), TAG_DIAL)
                    true
                }
                R.id.navigation_contact -> {
                    replaceFragment(ContactFragment.newInstance(), TAG_CONTACT)
                    true
                }
                R.id.navigation_favorite -> {
                    replaceFragment(FavoriteFragment.newInstance(), TAG_FAVORITE)
                    true
                }
                else -> false
            }
        }

        binding.bottomNavigationView.setOnItemReselectedListener {
            // No-op
        }
    }

    private fun replaceFragment(fragment: Fragment, tag: String) {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (currentFragment?.tag == tag) {
            return
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }

    companion object {
        private const val TAG_DIAL = "TAG_DIAL"
        private const val TAG_CONTACT = "TAG_CONTACT"
        private const val TAG_FAVORITE = "TAG_FAVORITE"
    }
}
