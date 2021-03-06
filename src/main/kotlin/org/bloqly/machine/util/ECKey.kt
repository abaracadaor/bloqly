package org.bloqly.machine.util

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.security.SecureRandom

class ECKey(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {

    private val secureRandom = SecureRandom()
    private val curve = SECNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)

    fun newKey(): ECKey {
        val generator = ECKeyPairGenerator()
        val keygenParams = ECKeyGenerationParameters(domain, secureRandom)
        generator.init(keygenParams)
        val keypair = generator.generateKeyPair()
        val privParams = keypair.private as ECPrivateKeyParameters
        val pubParams = keypair.public as ECPublicKeyParameters

        val privateKey = BigIntegers.asUnsignedByteArray(privParams.d).pad()
        val q = pubParams.q

        val publicKey = ECPoint.Fp(domain.curve, q.xCoord, q.yCoord, true).getEncoded(true)

        return ECKey(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }
}