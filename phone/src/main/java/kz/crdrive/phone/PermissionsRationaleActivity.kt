package kz.crdrive.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF05070B)).padding(24.dp)) {
                Text("CR Drive · данные здоровья", color = Color.White, fontSize = 24.sp)
                Text("С вашего разрешения приложение читает последний сон и измерение кислорода из Health Connect, чтобы показать их рядом с данными часов. Пульс и история поездки хранятся только на этом телефоне. CR Drive не отправляет данные здоровья на сервер и не ставит диагноз. Доступ можно отозвать в настройках Health Connect.",
                    color = Color.LightGray, fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
    }
}
