import { EvcsUpgradeComponent } from './evcsupgrade/evcsupgrade.component';
import { EvcsUpgradeModalComponent } from './evcsupgrade/modal/modal.component';
import { HomeElectricComponent } from './homeelectric/homeelectric.component';
import { HomeElectricModalComponent } from './homeelectric/modal/modal.component';
import { MiniupgradeComponent } from './miniupgrade/miniupgrade.component';
import { MiniupgradeModalComponent } from './miniupgrade/modal/modal.component';
import { NgModule } from '@angular/core';
import { SharedModule } from 'src/app/shared/shared.module';
import { AdvertisementComponent } from './advertisement.component';
import { SurveyComponent } from './survey/survey.component';

@NgModule({
    imports: [
        SharedModule,
    ],
    entryComponents: [
        EvcsUpgradeModalComponent,
        MiniupgradeModalComponent,
        HomeElectricModalComponent,
    ],
    declarations: [
        AdvertisementComponent,
        EvcsUpgradeComponent,
        EvcsUpgradeModalComponent,
        HomeElectricComponent,
        HomeElectricModalComponent,
        MiniupgradeComponent,
        MiniupgradeModalComponent,
        SurveyComponent,
    ],
    exports: [
        AdvertisementComponent
    ]
})
export class AdvertisementModule { }