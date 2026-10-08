package com.example.intonationtrainer.core.practice

import kotlin.math.*

/** YIN normalized difference detector. No UI or platform dependencies. */
class PitchDetector {
    fun detect(samples: DoubleArray, rate: Double): Pair<Double,Double>? {
        if(samples.size<512 || rate<=0) return null
        val maximum=min((rate/78).toInt(),samples.size/2-1)
        val minimum=max(2,(rate/1100).toInt())
        val length=samples.size-maximum
        val d=DoubleArray(maximum+1)
        var sum=0.0
        for(tau in 1..maximum) {
            var difference=0.0
            for(i in 0 until length) { val v=samples[i]-samples[i+tau]; difference+=v*v }
            sum+=difference
            d[tau]=if(sum>1e-15) difference*tau/sum else 1.0
        }
        var tau=minimum
        while(tau<maximum-1) {
            if(d[tau]<0.15) {
                while(tau+1<=maximum && d[tau+1]<d[tau]) tau++
                if(tau>=maximum) return null
                val a=d[tau-1]; val b=d[tau]; val c=d[tau+1]
                val denominator=a-2*b+c
                val offset=if(abs(denominator)>1e-12) (0.5*(a-c)/denominator).coerceIn(-1.0,1.0) else 0.0
                val frequency=rate/(tau+offset)
                return if(frequency>=Notes.hz(40)*2.0.pow(-0.5/12) && frequency<=Notes.hz(84)*2.0.pow(0.5/12)) frequency to (1-b).coerceIn(0.0,1.0) else null
            }
            tau++
        }
        return null
    }
}

/** Streaming box-filter downsampling, 2048-sample windows and 512-sample hops (~85/21 ms). */
class PitchPipeline(private val rate: Double, private val emit: (AnalysisFrame)->Unit) {
    private val factor=max(1,(rate/24000).toInt())
    private val analysisRate=rate/factor
    private val ring=DoubleArray(2048)
    private var write=0; private var filled=0; private var hop=0
    private var total=0L; private var group=0; private var accumulator=0.0
    private var energy=0.0; private var energyCount=0
    private var previousDb = -90.0
    private var valleyDb = -90.0
    private var peakDb = -90.0
    private var attackArmed=true
    private val detector=PitchDetector()
    fun accept(samples: FloatArray) {
        for(sample in samples) {
            total++; accumulator+=sample; group++; energy+=sample*sample; energyCount++
            if(group<factor) continue
            ring[write]=accumulator/factor; write=(write+1)%ring.size; filled=min(filled+1,ring.size); hop++
            accumulator=0.0; group=0
            if(hop<512) continue
            hop=0
            val db=(20*log10(sqrt(energy/max(1,energyCount)).coerceAtLeast(0.00003162))).coerceIn(-90.0,0.0)
            energy=0.0; energyCount=0
            peakDb=max(db,peakDb-0.5)
            if(db<peakDb-6 || db < -55) { attackArmed=true; valleyDb=min(valleyDb,db) }
            val onset=attackArmed && db>=-55 && db-valleyDb>=6 && db-previousDb>=3
            if(onset) { attackArmed=false; valleyDb=db; peakDb=db }
            if(!attackArmed) valleyDb=db
            previousDb=db
            if(filled<ring.size) continue
            val window=DoubleArray(ring.size) { ring[(write+it)%ring.size] }
            val result=if(db>=-55) detector.detect(window,analysisRate) else null
            emit(AnalysisFrame(total*1000.0/rate,result?.first,result?.second?:0.0,db,onset))
        }
    }
}
