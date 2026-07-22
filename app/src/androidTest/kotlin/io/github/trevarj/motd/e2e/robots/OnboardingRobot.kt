package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.trevarj.motd.e2e.FixtureArgs

internal class OnboardingRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun importSoju(args: FixtureArgs) {
        click("onboarding_forward_button")
        click("onboarding_choice_bouncer")
        click("onboarding_choice_soju")
        click("onboarding_forward_button")
        replace("network_host_field", args.host)
        replace("network_port_field", args.port.toString())
        replace("network_nick_field", args.nick)
        click("onboarding_forward_button")
        replace("bouncer_username_field", args.user)
        replace("bouncer_password_field", args.password)
        click("onboarding_forward_button")
        click("cert_trust_accept")
        clickPrefix("onboarding_bouncer_switch_")
        click("onboarding_forward_button")
        click("onboarding_forward_button")
        assertDisplayed("screen_chat_list")
    }
}
