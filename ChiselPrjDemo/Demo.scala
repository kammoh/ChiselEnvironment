/** Demo using skeleton for QAM demod */

package DemoXXX

// ------- Imports START -- DO NOT MODIFY BELOW
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._
import Chisel.{Complex => _, Mux => _, _}
import ChiselDSP._
// ------- Imports END -- OK TO MODIFY BELOW

/** Parameters externally passed via JSON file (can add defaults) */
case class JSONParams (
  QAMn: List[Int],            // List of supported n-QAM i.e. 4-QAM (QPSK), 16-QAM, 64-QAM, etc.
  frameSizes: List[Int],      // Supported frame sizes (see FFT sizes needed)
  softDemod: Boolean,         // If true, should do LLR calc, otherwise hard demod
  intBits: Int,               // Fixed in Qn.m notation --> n (# of integer bits, not including sign)
  fracBits: Int               // Fixed in Qn.m notation --> m (# frational bits, determines LSB)
)

/** IO Bundle showing off data types and how to pass parameters (actual vals are meaningless) */
class DemoIO(jsonParams:JSONParams) extends IOBundle {
  // ChiselDSP types (preferred because they keep track of meta info + perform optimizations with them)
  val b2 = DSPBool(INPUT)
  val u2 = DSPUInt(INPUT,(3,20))                    // (min,max) range; also: DSPUInt(DIR,max) -> assumes min = 0
  val d2 = DSPDbl(INPUT)
  val f2 = DSPFixed(INPUT,(1,15))                   // (int,frac) widths
  // Normal Chisel types
  val b0 = Bits(INPUT,width=5)
  val b1 = Bool(INPUT)
  val u1 = UInt(INPUT,width=5)
  val s1 = SInt(INPUT,width=5)
  val f1 = Fixed(INPUT,width = 17, fracWidth = 15)  // width = int width + frac width + 1 (sign)
  val d1 = Dbl(INPUT)
  val f0 = Flo(INPUT)
  
  // Demonstrates customizable IO --> IO pin is not generated if a Literal/constant is assigned 
  val optionalIO = if (jsonParams.softDemod) DSPUInt(0) else DSPUInt(INPUT,(3,jsonParams.frameSizes.max))
  
  // Example of how to create Complex
  val complex0 = Complex(DSPDbl(INPUT),DSPDbl(INPUT))
}

/** Special way to create a module that uses a generic type to easily switch between Double/Fixed point testing.
  * The correct type 'gen' must be passed in main. The second argument (true) @ GenDSPModule(gen,true) indicates
  * DecoupledIO is desired.
  */
class DemoXXX [T <: DSPQnm[T]](gen : => T, jsonParams: JSONParams) extends GenDSPModule (gen, true) {

  /** Inline IO Bundle allows module methods double2T (double --> literal)
    * and T (customized Fixed widths) to be directly used.
    * Note IO should be in IOBundle.
    */
  class DemoXXXIO extends IOBundle {
    // Input either signed DSPFixed or DSPDbl, as set by gen
    val symbolIn = Complex(gen,gen).asInput
    // # of "hard" bits required is set by the maximum n-QAM supported 
    // (toBitWidth converts from an integer to # of bits required to represent it)
    // Note for 4-QAM, the UInt range is [0,3]
    // For Fixed, output notation is Qn.m (width = n + m + 1 for sign bit)
    // When performing hard decoding, n,m = 0, so each element of the vec should
    // only be 1 bit wide (using the sign bit)
    // When performing hard decoding, n = 0, m = ??? -- you should determine what ??? is (15 is a placeholder)
    val m = if (jsonParams.softDemod) 15 else 0
    val demodOut = Vec(DSPUInt.toBitWidth(jsonParams.QAMn.max-1), T(OUTPUT,(0,m)))
    // If the bits of demodOut are interpreted as signed BigInt rather than fixed (i.e. renormalize wrt LSB), 
    // a large positive number is a very confident 0, and a small positive number is a less confident 0.
    // Negative #'s are associated with confidence for being a 1. We chose the sign of the LLR as positive <-> 0
    // bit because then the 2's complement sign bit of the LLR is the same as the hard decoder decision.
    // People aren't generally consistent about choosing positive LLRs to correspond to 0 or 1, so we choose
    // one with a convenient interpretation in this context.
    // Offset of the input sample relative to frame size (needs to support up to max frame size)
    val offsetIn = DSPUInt(INPUT,jsonParams.frameSizes.max-1)
    // If symbolIn --> corresponding demodOut takes n cycles, offsetOut should be offsetIn delayed n clocks
    val offsetOut = DSPUInt(OUTPUT,jsonParams.frameSizes.max-1)
    val reset = DSPBool(INPUT)
  }

  // Instantiate IO objects
  val demoIO = new DemoXXXIO
  val i = new DemoIO(jsonParams)
  // Creates a new instance of DemoIO with port directions flipped (i.e. to output)
  val o = new DemoIO(jsonParams).flip

  // Delay offset by how long it takes to finish computation (n)
  // Note: Instead of doing Reg(x) or Pipe(x,n) do x.reg() or x.pipe(n) to keep meta info
  demoIO.offsetOut := demoIO.offsetIn.pipe(5)
  
  class LitBundle extends Bundle {
    // Create literals (that can be peeked)
    val b2 = DSPBool(true)
    val u2 = DSPUInt(17)                    
    val d2a = DSPDbl(-3.333)
    val d2b = DSPDbl(3.333)
    val f2a = DSPFixed(-0.78,(1,15))    
    val f2b = DSPFixed(0.78,(1,15))                 
    val b1 = Bool(true)
    val u1 = UInt(17,width=5)
    val s1a = SInt(-10,width=5)
    val s1b = SInt(10,width=5)
    val f1a = Fixed(-1.222,width = 17, fracWidth = 15)  
    val f1b = Fixed(1.222,width = 17, fracWidth = 15)  
    val d1a = Dbl(-3.33)
    val d1b = Dbl(3.33)
    val f0a = Flo(-3.33)
    val f0b = Flo(3.33)
    
    // Create literal from gen type {DSPFixed or DSPDouble} -- can override
    // default fixed intWidth, fracWidth by doing double2T(#,(intWidth,fracWidth))
    // or double2T(#,fracWidth) which determines # of integer bits needed for #
    val gena = double2T(-1.3)
    val genb = double2T(1.3)
  }
  val lits = new LitBundle
  // Easily debug internal signals that aren't connected to output ports 
  // Can wrap signals in an aggregate (Vec, Bundle) to just do debug(aggregateName) OR
  // can just debug(signalName) i.e. debug(b2) if it wasn't in LitBundle
  debug(lits)
  
  // Shorthand to connect all DemoIO inputs to outputs
  //i <> o
 
  // You can reassign to nodes; last assignment takes precedence
  // This is how you access individual [real, imag] components of complex
  // Operator types should match (gen should match gen, DSPDbl should match DSPDbl)
  // Arithmetic shift oeprations, normal +,-,* (no divide), Mux c ? tc : fc
  o.complex0.imag := (i.complex0.real >> 3) + DSPDbl(3)
  o.complex0.real := i.complex0.imag * DSPDbl(3) + Mux(i.b2,i.complex0.imag,i.complex0.real)

  // You can make use of DecoupledIO (ready,valid), which you enabled in the Module creation
  decoupledO.ready := decoupledI.ready
  decoupledO.valid := decoupledI.valid

  /** Miscellaneous test IO */
  class TestIO extends IOBundle {
    val countOut = Vec(3,DSPUInt(OUTPUT,10))
  }
  val testIO = new TestIO

  // Modcounter is a DSPModule, not GenDSPModule (you don't need to specify a generic Fixed/Dbl type)
  // See ChiselDSP/Modules/Counters.scala for how you should create a ModCounter
  // 3 Counteres are created, indexed 0 to 2
  val CounterTest = (0 until 3).map(x => ModCounter(10,4,"YourCounterName") )
  // Alternative to for loop: e = element, i = index of element
  // Mapping signals to ports
  CounterTest.zipWithIndex.foreach{
    case(e,i) => {
      e.x.inc := DSPUInt(2)
      e.x.modN := DSPUInt(3)
      e.iCtrl.change := DSPBool(true)
      e.iCtrl.reset := DSPBool(demoIO.reset)
      e.iCtrl.wrap := DSPBool(false)
      testIO.countOut(i) := e.x.out
    }
  }

}

object DemoXXX {

  def main(args: Array[String]): Unit = {

    val demoArgs = args.slice(1, args.length)

    // Needed for JSON parameter extraction to case class
    // To see how to set case class parameters without a JSON file, check out
    // ChiselDSP/Modules/Counters.scala
    implicit val formats = DefaultFormats
    val jsonContents = scala.io.Source.fromFile("src/main/scala/Demo.json").getLines.mkString
    val json = parse(jsonContents)
    val paramsDemoXXX = json.extract[JSONParams]
    Status("User parameters: " + jsonContents.replace("\t","\n").replace("{","").replace("}",""))

    // SBT parameters (used to set Fixed/Dbl)
    val paramsSBT = """-params_(.*)""".r.findFirstMatchIn(args(0))
    val isFixed = paramsSBT.get.group(1).toBoolean
    val mode = if (isFixed) "fixed point" else "double precision floating point"
    Status("Compiling XXX in " + mode + " mode")

    // Setup module + tester
    /*val gen = if (isFixed) DSPFixed((paramsDemoXXX.intBits,paramsDemoXXX.fracBits)) else DSPDbl()
    val topModule = new DemoXXX({gen}, paramsDemoXXX)
    chiselMainTest(demoArgs, () => DSPModule(topModule)) {
        c => new DemoXXXTests(c)
    }*/

    chiselMainTest(demoArgs, () => DSPModule(new DemoXXX({DSPFixed(INPUT,(paramsDemoXXX.intBits,paramsDemoXXX.fracBits))}, paramsDemoXXX))) {
      c => new DemoXXXTests(c)
    }

  }

}

/** Special way to test a module that uses a generic type to easily switch between Double/Fixed point testing. */
class DemoXXXTests[T <: DemoXXX[_ <: DSPQnm[_]]](c: T) extends DSPTester(c) {
  reset(5)      // Hold reset for 5 cycles (reset is default Chisel reset; unused in Demo module)
  step(5)       // Step 5 cycles
  peek(c.lits)  // Peek elements of a bundle
}