package com.walletconnect.modal.ui.components.qr

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.github.alexzhirkevich.customqrgenerator.QrData
import com.github.alexzhirkevich.customqrgenerator.vector.QrCodeDrawable
import com.github.alexzhirkevich.customqrgenerator.vector.createQrVectorOptions
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorBallShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorColor
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorFrameShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorLogoPadding
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorPixelShape
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.walletconnect.modal.ui.ComponentPreview
import com.walletconnect.modalcore.R
import androidx.compose.ui.graphics.Color as ComposeColor


@Composable
fun WalletConnectQRCode(
    qrData: String,
    primaryColor: ComposeColor,
    logoColor: ComposeColor,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val qrDrawable = remember(qrData, context, logoColor, primaryColor) {
        QrCodeDrawable(
            data = QrData.Url(qrData),
            options = createQROptions(context, logoColor, primaryColor)
        )
    }

    Image(
        painter = rememberDrawablePainter(drawable = qrDrawable),
        contentDescription = "content description",
        modifier = Modifier.aspectRatio(1f).then(modifier)
    )
}

@Preview
@Composable
private fun QRCodePreview() {
    ComponentPreview {
        WalletConnectQRCode(
            "Preview qr code data with wallet connect logo inside",
            ComposeColor.White,
            ComposeColor(0xFF3496ff)
        )
    }
}

private fun createQROptions(
    context: Context,
    logoColor: ComposeColor,
    primaryColor: ComposeColor,
) =
    createQrVectorOptions {
        padding = .0f

        logo {
            val rawDrawable = ContextCompat.getDrawable(context, R.drawable.ic_wallet_connect_qr_logo)
            val wrappedDrawable = DrawableCompat.wrap(rawDrawable!!)
            DrawableCompat.setTint(wrappedDrawable, logoColor.toArgb())
            drawable = wrappedDrawable
            size = .2f
            padding = QrVectorLogoPadding.Natural(.2f)
        }

        colors {
            dark = QrVectorColor.Solid(primaryColor.toArgb())
        }
        shapes {
            frame = QrVectorFrameShape.RoundCorners(.4f)
            ball = QrVectorBallShape.RoundCorners(.2f)
            darkPixel = QrVectorPixelShape.RoundCornersVertical(.95f)
        }
    }
