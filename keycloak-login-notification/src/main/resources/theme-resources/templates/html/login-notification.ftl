<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("loginNotificationIntroHtml", username, realmName))?no_esc}
<#if afterInactivity>${kcSanitize(msg(inactiveInDays?then("loginNotificationReasonInactivityHtml", "loginNotificationReasonInactivityHoursHtml"), inactiveInDays?then(inactiveDays, inactiveHours)))?no_esc}</#if>
<#if fromNewIp>${kcSanitize(msg("loginNotificationReasonNewIpHtml"))?no_esc}</#if>
${kcSanitize(msg("loginNotificationDetailsHtml", loginTime, ipAddress, application, userAgent))?no_esc}
${kcSanitize(msg("loginNotificationActionHtml", accountUrl))?no_esc}
</@layout.emailLayout>
