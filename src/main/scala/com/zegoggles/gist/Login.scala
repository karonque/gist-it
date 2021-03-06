package com.zegoggles.gist

import android.graphics.Bitmap
import android.accounts.{Account, AccountManager, AccountAuthenticatorActivity}
import android.webkit._
import java.lang.String
import android.os.{Handler, Bundle}
import actors.Futures
import Implicits._
import android.webkit.WebSettings.ZoomDensity
import android.app.{AlertDialog, ProgressDialog}
import android.net.Uri
import android.text.TextUtils
import org.apache.http.HttpStatus
import java.io.IOException
import android.widget.Toast

class Login extends AccountAuthenticatorActivity with Logger with ApiActivity with TypedActivity {
  val handler = new Handler()
  lazy val view = findView(TR.webview)

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.login)

    view.getSettings.setDefaultZoom(ZoomDensity.FAR)
    view.getSettings.setJavaScriptEnabled(true)
    view.getSettings.setBlockNetworkImage(false)
    view.getSettings.setLoadsImagesAutomatically(true)

    val progress = ProgressDialog.show(this, null, getString(R.string.loading_login), false)
    view.setWebViewClient(new WebViewClient() {

      override def onPageStarted(view: WebView, url: String, favicon: Bitmap) {
        super.onPageStarted(view, url, favicon)
        progress.show()
        if (android.os.Build.VERSION.SDK_INT <= 7) {
          // in 2.1 shouldOverrideUrlLoading doesn't get called
          if (shouldOverrideUrlLoading(view, url)) {
            view.stopLoading()
          }
        }
      }

      override def shouldOverrideUrlLoading(view: WebView, url: String) = {
        super.shouldOverrideUrlLoading(view, url)
        if (url.startsWith(api.redirect_uri)) {
          Uri.parse(url).getQueryParameter("code") match {
            case code:String => exchangeToken(code)
            case _           => warn("no code found in redirect uri")
          }
          true
        } else false
      }

      override def onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        showConnectionError(if (TextUtils.isEmpty(description)) None else Some(description))
      }


      override def onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        view.loadUrl("javascript:document.getElementById('login_field').focus();")
        try { progress.dismiss() } catch { case e:IllegalArgumentException => warn("", e) }
      }
    })

    if (isConnected) {
      removeAllCookies()
      view.loadUrl(api.authorizeUrl)
    } else showConnectionError(None)
  }

  def exchangeToken(code: String) {
    val progress = ProgressDialog.show(this, null, getString(R.string.loading_token), false)
    Futures.future {
      try {
        val token = api.exchangeToken(code).getOrElse(throw new IOException("could not get token"))
        val resp = api.get(Request("/user", "access_token"->token.access))
        resp.getStatusLine.getStatusCode match {
          case HttpStatus.SC_OK =>
            User(resp.getEntity).map { user =>
              handler.post {
                setAccountAuthenticatorResult(
                  addAccount(user.login, token,
                    "id" -> user.id.toString,
                    "name" -> user.name,
                    "email" -> user.email))
              }
            }
          case c =>
            log("invalid status ("+c+") "+resp.getStatusLine)
            Toast.makeText(this, R.string.loading_token_failed, Toast.LENGTH_LONG).show()
        }
      }
      catch {
        case e:IOException => warn("error", e)
        Toast.makeText(this, R.string.loading_token_failed, Toast.LENGTH_LONG).show()
      }
      finally { handler.post { progress.dismiss(); finish() } }
    }
  }

  def addAccount(name: String, token: Token, data: (String, String)*): Bundle = {
    val account = new Account(name, accountType)
    val am = AccountManager.get(this)
    am.addAccountExplicitly(account, token.access, null)
    am.setAuthToken(account, "access", token.access)
    for ((k, v) <- data) am.setUserData(account, k, v)
    val b = new Bundle()
    b.putString(AccountManager.KEY_ACCOUNT_NAME, name)
    b.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType)
    b
  }

  def showConnectionError(message: Option[String]) {
    var error = getString(R.string.login_error_no_connection_message)
    message.map(m => error += " (" + m + ")")
    new AlertDialog.Builder(this)
      .setMessage(error)
      .setIcon(android.R.drawable.ic_dialog_alert)
      .setPositiveButton(android.R.string.ok, () => finish())
      .show()
  }

  def removeAllCookies() {
    CookieSyncManager.createInstance(this)
    CookieManager.getInstance().removeAllCookie()
  }

  override def onDestroy() {
    view.stopLoading()
    super.onDestroy()
  }
}

