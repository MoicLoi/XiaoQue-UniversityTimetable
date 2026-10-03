package com.xiqueer.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    initialUsername: String,
    /** 已选学校名;为空表示还没选过,提示用户去选。 */
    schoolName: String,
    busy: Boolean,
    error: String?,
    onPickSchool: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // 名字刻意不叫「喜鹊儿」:那是厂商的产品名。
        // 这一页下面写着"非官方客户端",名字却挂着厂商名的话,两句话会互相打架。
        Text("小鹊课表", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = XqColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("课表 · 成绩 · 通知", color = XqColors.TextSecondary)
        Spacer(Modifier.height(36.dp))

        // 学校:显示**名字**,不显示代码。点一下去搜索选择。
        // 官方客户端也是"选学校"的做法 —— 让用户记住 10162 这种数字没有道理。
        //
        // ⚠️ 为什么不用 OutlinedTextField:两条路都走过,都不行 ——
        //   1. `enabled = false` 的输入框**会吃掉点击**,挂在外层的 clickable 不触发;
        //   2. 加一层透明覆盖层能点了,但覆盖层的 clickable 会**合并语义**,
        //      辅助功能(uiautomator / 读屏)再也读不到里面的文字 —— 一个看不见的输入框。
        // 所以这里手写一个只读行:文字是 Box 的内容,clickable 与文字在同一个节点上,
        // 既可点、又能被辅助功能正确读出。
        Column(Modifier.fillMaxWidth()) {
            Text(
                "学校",
                color = XqColors.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, XqColors.TextTertiary, RoundedCornerShape(6.dp))
                    .clickable(onClick = onPickSchool)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    schoolName.ifEmpty { "点击选择学校" },
                    color = if (schoolName.isEmpty()) XqColors.TextTertiary else XqColors.TextPrimary,
                    fontSize = 16.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("学号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = { onLogin(username, password) },
            // 学校没选就不能登录 —— 没有默认学校,这是刻意的
            enabled = !busy && schoolName.isNotEmpty() && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(18.dp))
        // 措辞刻意克制:早先写的是「数据来自学校授权的喜鹊儿接口」——
        // 我们没有任何依据说"学校授权",这种话既不准也可能给用户惹麻烦。
        // 现在只说事实:非官方、无隶属关系、用公开接口。
        Text(
            "凭据只加密保存在本机(Android Keystore)。这是非官方客户端,与学校及厂商均无隶属关系," +
                "数据来自喜鹊儿的公开接口。",
            color = XqColors.TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
