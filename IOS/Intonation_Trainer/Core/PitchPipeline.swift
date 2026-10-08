import Foundation

final class PitchDetector {
    func detect(_ samples:[Double],rate:Double) -> (Double,Double)? {
        guard samples.count>=512,rate>0 else { return nil }
        let maximum=min(Int(rate/78),samples.count/2-1)
        let minimum=max(2,Int(rate/1100))
        let length=samples.count-maximum
        var d=Array(repeating:0.0,count:maximum+1)
        var sum=0.0
        for tau in 1...maximum {
            var difference=0.0
            for i in 0..<length { let v=samples[i]-samples[i+tau]; difference+=v*v }
            sum+=difference; d[tau]=sum>1e-15 ? difference*Double(tau)/sum:1
        }
        var tau=minimum
        while tau<maximum-1 {
            if d[tau]<0.15 {
                while tau+1<=maximum && d[tau+1]<d[tau] { tau+=1 }
                if tau>=maximum { return nil }
                let a=d[tau-1], b=d[tau], c=d[tau+1]
                let denominator=a-2*b+c
                let offset=abs(denominator)>1e-12 ? max(-1,min(1,0.5*(a-c)/denominator)):0
                let frequency=rate/(Double(tau)+offset)
                return frequency>=Notes.hz(40)*pow(2,-0.5/12) && frequency<=Notes.hz(84)*pow(2,0.5/12) ? (frequency,max(0,min(1,1-b))):nil
            }
            tau+=1
        }
        return nil
    }
}

final class PitchPipeline {
    private let rate:Double, factor:Int, analysisRate:Double
    private let emit:(AnalysisFrame)->Void
    private var ring=Array(repeating:0.0,count:2048)
    private var write=0, filled=0, hop=0, total=0, group=0
    private var accumulator=0.0, energy=0.0, energyCount=0
    private var previousDb = -90.0, valleyDb = -90.0, peakDb = -90.0
    private var attackArmed=true
    private let detector=PitchDetector()
    init(rate:Double,emit:@escaping(AnalysisFrame)->Void) { self.rate=rate; factor=max(1,Int(rate/24000)); analysisRate=rate/Double(factor); self.emit=emit }
    func accept(_ samples:[Float]) {
        for sample in samples {
            total+=1; accumulator+=Double(sample); group+=1; energy+=Double(sample*sample); energyCount+=1
            if group<factor { continue }
            ring[write]=accumulator/Double(factor); write=(write+1)%ring.count; filled=min(filled+1,ring.count); hop+=1
            accumulator=0; group=0
            if hop<512 { continue }; hop=0
            let db=max(-90,min(0,20*log10(max(sqrt(energy/Double(max(1,energyCount))),0.00003162))))
            energy=0; energyCount=0; peakDb=max(db,peakDb-0.5)
            if db<peakDb-6 || db < -55 { attackArmed=true; valleyDb=min(valleyDb,db) }
            let onset=attackArmed && db >= -55 && db-valleyDb>=6 && db-previousDb>=3
            if onset { attackArmed=false; valleyDb=db; peakDb=db }
            if !attackArmed { valleyDb=db }; previousDb=db
            if filled<ring.count { continue }
            let window=(0..<ring.count).map { ring[(write+$0)%ring.count] }
            let result=db >= -55 ? detector.detect(window,rate:analysisRate):nil
            emit(AnalysisFrame(timeMs:Double(total)*1000/rate,frequency:result?.0,confidence:result?.1 ?? 0,levelDb:db,onset:onset))
        }
    }
}
